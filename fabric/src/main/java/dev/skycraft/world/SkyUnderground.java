// SkyCraft+ : the Minecraft underground under Skyrim
package dev.skycraft.world;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Real Minecraft depth under Skyrim's open world. A hidden dimension, skycraft:underground, is a
 * plain vanilla overworld (vanilla's own generator, biomes and structures, nothing changed). Where
 * digging reaches into Skyrim's ground, the block at the matching spot of that world is brought up
 * into the mirror world: the two line up so Skyrim's sea level is Minecraft's (63) and bedrock is at
 * Minecraft's -64 below it. Where that world has a cave (air, water or lava), the cell opens and the
 * cave is revealed outwards, nearest the player first and as much as 4 ms a tick allows, only
 * inside Skyrim's ground (cells Skyrim's shape says are inside it, or deep under its land; regions
 * it hasn't sent yet wait). Above vanilla's own terrain but under a
 * Skyrim mountain is stone. Setting: deepUnderground in config/skycraft.properties (off: upstream's
 * dirt, stone and bedrock five blocks down).
 */
public final class SkyUnderground {
	public static final ResourceKey<Level> UNDERGROUND = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "underground"));
	private static final int VANILLA_SEA = 63;
	private static final int VANILLA_BOTTOM = -64;
	private static final int SURFACE_SKIN = 5;     // vanilla's own surface layers (grass, dirt, sand, sea floor) count as stone
	private static final long REVEAL_NANOS = 4_000_000L; // per tick: a cave opens as fast as this allows
	private static final int AHEAD_CHUNKS = 4;          // the hidden world generated this far around the host
	private static final int MAX_WAITING = 20000;

	private static boolean enabled = true;
	private static int caveMonsterCap = 10;

	/**
	 * Cells whose neighbours are to be revealed (a cave opening up), for one Skyrim world: nearest
	 * the host first, so the cave in front of them opens before its far corners.
	 */
	private static double focusX, focusY, focusZ;
	private static final it.unimi.dsi.fastutil.longs.LongComparator NEAREST = (a, b) -> Double.compare(distanceToFocus(a), distanceToFocus(b));
	private static it.unimi.dsi.fastutil.longs.LongHeapPriorityQueue OPENING = new it.unimi.dsi.fastutil.longs.LongHeapPriorityQueue(NEAREST);
	/** Cells next to an opening cave whose part of Skyrim's shape hasn't arrived yet. */
	private static final LongOpenHashSet WAITING = new LongOpenHashSet();
	private static int openingWorld;
	private static int logged;

	private SkyUnderground() {
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			loadSettings();
			OPENING.clear();
			WAITING.clear();
			FOR_CHUNK.clear(); // SkyCraft+ : world switcher (another world may open in the same session)
			lastShift = UNSET;
		});
		ServerTickEvents.END_SERVER_TICK.register(SkyUnderground::tick);
	}

	private static void loadSettings() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("skycraft.properties");
		Properties p = new Properties();
		if (Files.exists(file)) {
			try (Reader in = Files.newBufferedReader(file)) {
				p.load(in);
			} catch (IOException e) {
				SkyCraft.LOG.warn("SkyCraft: couldn't read {}: {}", file, e.toString());
			}
		}
		enabled = Boolean.parseBoolean(p.getProperty("deepUnderground", "true").trim());
		boolean caves = Boolean.parseBoolean(p.getProperty("caveMonsters", "true").trim());
		int cap = 10;
		try {
			cap = Math.max(0, Integer.parseInt(p.getProperty("caveMonsterCap", "10").trim()));
		} catch (NumberFormatException e) {
			// keep the default
		}
		caveMonsterCap = caves ? cap : 0;
		SkyCraft.LOG.info("SkyCraft: Minecraft's underground under Skyrim {}", enabled ? "on" : "off");
	}

	/** How many monsters the dark of the underground holds around the host (0: none; SkyCaves). */
	static int caveMonsterCap() {
		return enabled ? caveMonsterCap : 0;
	}

	/** This dug cell of Skyrim's ground (a Proto.DIG_* material) comes from the hidden world. */
	public static boolean takes(ServerLevel level, int material) {
		return enabled && level.dimension() == Level.OVERWORLD && level.getServer().getLevel(UNDERGROUND) != null
			&& (material == Proto.DIG_STONE || material == Proto.DIG_NONE || material == Proto.DIG_BEDROCK);
	}

	/** How far under Skyrim's land a point is surely ground, whatever Skyrim has (or hasn't) sent around it. */
	public static final double DEEP = 8.0;

	/** The underground is on (config/skycraft.properties deepUnderground). */
	public static boolean enabled() {
		return enabled;
	}

	/**
	 * Well under Skyrim's land: solid ground. Looking up for the nearest surface (SkyDig.Probe)
	 * there finds the sunk-in undersides of rocks and took them for an overhang with open air
	 * under it, leaving holes out of the world in the walls of deep mines. Any thread.
	 */
	public static boolean deep(double x, double y, double z) {
		return enabled && Boolean.TRUE.equals(SkyGround.underLoosely(x, y, z, DEEP)); // (all sixteen heights known was asked before: gaps where some weren't)
	}

	/** The hidden world under this level, if it's the open world's and the underground is on. */
	public static @Nullable ServerLevel hiddenUnder(ServerLevel level) {
		return enabled && level.dimension() == Level.OVERWORLD ? level.getServer().getLevel(UNDERGROUND) : null;
	}

	/** Under Skyrim's land at all (no sun reaches there). Any thread. */
	public static boolean underLand(double x, double y, double z) {
		return enabled && Boolean.TRUE.equals(SkyGround.underLoosely(x, y, z, 1.0));
	}

	/**
	 * How the two line up, per block column: hidden y = mirror y + shift. Skyrim's land, smoothed
	 * over ~24 blocks, sits at the hidden world's TOP, just under vanilla's own surface, so caves
	 * start soon below Skyrim's ground and bedrock is ~125 blocks down wherever you are, like in
	 * Minecraft. Steep land bends caves a little. Each column's shift is saved with the world the
	 * first time it's used and never changes; where Skyrim's land around hasn't been seen, the
	 * nearest saved column's (or the last one used) carries on.
	 */
	private static final int TOP = 61;
	private static final int UNSET = Integer.MIN_VALUE;
	public static final AttachmentType<List<Integer>> SHIFT = AttachmentRegistry.<List<Integer>>builder()
		.persistent(Codec.INT.listOf())
		.buildAndRegister(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "underground_shift"));
	private static int lastShift = UNSET;

	static int shiftAt(ServerLevel level, BlockPos pos) {
		LevelChunk chunk = level.getChunkAt(pos);
		int index = (pos.getX() & 15) | (pos.getZ() & 15) << 4;
		List<Integer> shifts = chunk.getAttached(SHIFT);
		if (shifts != null && shifts.size() == 256 && shifts.get(index) != UNSET) {
			return shifts.get(index);
		}
		int shift;
		double land = SkyGround.smoothed(pos.getX(), pos.getZ());
		if (!Double.isNaN(land)) {
			shift = TOP - (int) Math.floor(land);
		} else {
			Integer near = nearestSaved(level, pos);
			shift = near != null ? near : lastShift != UNSET ? lastShift : Math.round(VANILLA_SEA - SkyWorld.seaLevel());
		}
		Integer whole = structureShift(level, pos, shift);
		if (whole != null) {
			shift = whole;
		}
		List<Integer> updated = new ArrayList<>(shifts != null && shifts.size() == 256 ? shifts : Collections.nCopies(256, UNSET));
		updated.set(index, shift);
		chunk.setAttached(SHIFT, updated);
		lastShift = shift;
		return shift;
	}

	/**
	 * An underground structure (stronghold, mineshaft, trial chambers, ancient city) comes up whole:
	 * one shift for all its columns, so its floors stay level and an End portal stays in one piece
	 * (column by column, Skyrim's sloping land stepped them). Chosen from Skyrim's land at its
	 * middle the first time any of it is dug, and saved with the world.
	 */
	public static final AttachmentType<Map<String, Integer>> STRUCTURE_SHIFTS = AttachmentRegistry.<Map<String, Integer>>builder()
		.persistent(Codec.unboundedMap(Codec.STRING, Codec.INT))
		.buildAndRegister(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "underground_structure_shift"));
	private static final Map<String, StructureStart> STRUCTURE_STARTS = new HashMap<>();
	private static final java.util.Set<String> PORTAL_ROOMS_TOLD = new java.util.HashSet<>();
	private static final java.util.Set<String> LEVEL_STRUCTURES = java.util.Set.of("stronghold", "mineshaft", "mineshaft_mesa", "trial_chambers", "ancient_city");

	private static @Nullable Integer structureShift(ServerLevel level, BlockPos pos, int fallback) {
		ServerLevel hidden = level.getServer().getLevel(UNDERGROUND);
		ChunkAccess here = hidden == null ? null : hidden.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
		if (here == null) {
			return null;
		}
		for (Map.Entry<Structure, LongSet> entry : here.getAllReferences().entrySet()) {
			Structure structure = entry.getKey();
			Identifier id = hidden.registryAccess().lookupOrThrow(Registries.STRUCTURE).getKey(structure);
			// By name: the stronghold's generation step is "surface_structures" (it's buried).
			if (id == null || !LEVEL_STRUCTURES.contains(id.getPath())) {
				continue;
			}
			for (long ref : entry.getValue()) {
				String key = id + "@" + ref;
				String boxKey = hidden.getSeed() + "|" + key;
				StructureStart found = STRUCTURE_STARTS.get(boxKey);
				if (found == null) {
					ChunkPos start = ChunkPos.unpack(ref);
					ChunkAccess startChunk = hidden.getChunk(start.x(), start.z(), ChunkStatus.STRUCTURE_STARTS, true);
					found = startChunk == null ? null : startChunk.getStartForStructure(structure);
					if (found == null || !found.isValid()) {
						continue;
					}
					STRUCTURE_STARTS.put(boxKey, found);
				}
				BoundingBox box = found.getBoundingBox();
				if (pos.getX() < box.minX() || pos.getX() > box.maxX() || pos.getZ() < box.minZ() || pos.getZ() > box.maxZ()) {
					continue;
				}
				Map<String, Integer> saved = level.getAttached(STRUCTURE_SHIFTS);
				Integer shift = saved == null ? null : saved.get(key);
				if (shift == null) {
					BlockPos middle = box.getCenter();
					double land = SkyGround.smoothed(middle.getX(), middle.getZ());
					shift = Double.isNaN(land) ? fallback : TOP - (int) Math.floor(land);
					Map<String, Integer> updated = new HashMap<>(saved != null ? saved : Map.of());
					updated.put(key, shift);
					level.setAttached(STRUCTURE_SHIFTS, updated);
					SkyCraft.LOG.info("SkyCraft: underground: {} at {} comes up level (shift {})", id, middle.toShortString(), shift);
				}
				if (PORTAL_ROOMS_TOLD.add(boxKey)) {
					// Where its End portal is (here, not in the hidden world), for whoever goes looking.
					for (StructurePiece piece : found.getPieces()) {
						if (piece instanceof StrongholdPieces.PortalRoom) {
							BlockPos room = piece.getBoundingBox().getCenter();
							SkyCraft.LOG.info("SkyCraft: underground: {}'s End portal room is at {} {} {}", id, room.getX(), room.getY() - shift, room.getZ());
						}
					}
				}
				return shift;
			}
		}
		return null;
	}

	/** The nearest column with a saved shift, within 8 blocks, in loaded chunks. */
	private static @Nullable Integer nearestSaved(ServerLevel level, BlockPos pos) {
		for (int r = 1; r <= 8; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
						continue;
					}
					int x = pos.getX() + dx, z = pos.getZ() + dz;
					LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
					List<Integer> shifts = chunk == null ? null : chunk.getAttached(SHIFT);
					if (shifts != null && shifts.size() == 256) {
						int s = shifts.get((x & 15) | (z & 15) << 4);
						if (s != UNSET) {
							return s;
						}
					}
				}
			}
		}
		return null;
	}

	/** The hidden world's chunk for this cell is generated (it's generated in the background, never on the spot). */
	public static boolean ready(ServerLevel level, BlockPos pos) {
		ServerLevel hidden = level.getServer().getLevel(UNDERGROUND);
		return hidden != null && hidden.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
	}

	private record Waiting(int world, BlockPos pos, int material) {
	}

	/** Cells to dig once their hidden chunk is ready, by that chunk. */
	private static final Map<Long, List<Waiting>> FOR_CHUNK = new HashMap<>();

	/** Dug where the hidden chunk isn't generated yet: generate it in the background, then dig. */
	public static void waitFor(ServerLevel level, int world, BlockPos pos, int material) {
		ServerLevel hidden = level.getServer().getLevel(UNDERGROUND);
		if (hidden == null) {
			return;
		}
		ChunkPos chunk = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
		List<Waiting> list = FOR_CHUNK.computeIfAbsent(chunk.pack(), k -> new ArrayList<>());
		if (list.isEmpty()) {
			hidden.getChunkSource().addTicketWithRadius(TicketType.PORTAL, chunk, 0);
		}
		if (list.size() < 4096) {
			list.add(new Waiting(world, pos.immutable(), material));
		}
	}

	/** Brings the hidden world's block for this (already dug) cell up into the mirror world. */
	public static void bringUp(ServerLevel level, int world, BlockPos pos) {
		ServerLevel hidden = level.getServer().getLevel(UNDERGROUND);
		if (hidden == null) {
			return;
		}
		int vy = pos.getY() + shiftAt(level, pos);
		if (vy < VANILLA_BOTTOM) {
			level.setBlock(pos, Blocks.BEDROCK.defaultBlockState(), 3);
			return;
		}
		BlockPos at = new BlockPos(pos.getX(), vy, pos.getZ());
		int surface = hidden.getHeight(Heightmap.Types.OCEAN_FLOOR, pos.getX(), pos.getZ());
		if (vy >= surface - SURFACE_SKIN) {
			// Above vanilla's own terrain but under Skyrim's: a mountain's body.
			level.setBlock(pos, SkyDig.stoneOrOre(level.getSeed(), pos), 3);
			return;
		}
		BlockState state = hidden.getBlockState(at);
		if (state.isAir() || !state.getFluidState().isEmpty() && state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) {
			// A cave (or a flooded one): it opens, and so does the rest of it.
			if (!state.isAir()) {
				level.setBlock(pos, state, 3);
			}
			bringUpEntities(hidden, level, at, pos);
			open(world, pos);
			return;
		}
		if (state.getBlock() instanceof FallingBlock) {
			// Sand and gravel would fall into the not yet revealed cell below: reveal it first.
			BlockPos below = pos.below();
			if (!SkyDig.isDug(level, world, below)) {
				SkyDig.digCell(level, world, below, Proto.DIG_STONE);
			}
		}
		level.setBlock(pos, state, 3);
		BlockEntity source = hidden.getBlockEntity(at);
		if (source != null) {
			// Chests (with their loot tables), spawners, shriekers, End portal frames' data...
			CompoundTag tag = source.saveWithFullMetadata(hidden.registryAccess());
			BlockEntity copy = BlockEntity.loadStatic(pos, state, tag, level.registryAccess());
			if (copy != null) {
				level.setBlockEntity(copy);
			}
		}
		if (!state.canOcclude()) {
			bringUpEntities(hidden, level, at, pos); // a minecart on rails, say
		}
		if (!state.isSolidRender()) {
			// Doors, stairs, fences, rails, cobwebs: one sees past them, so what's behind opens too (a
			// stronghold's rooms waited behind plain stone at each door until a block there was broken).
			open(world, pos);
		}
		if (logged < 20 && !state.is(Blocks.STONE) && !state.is(Blocks.DEEPSLATE)) {
			logged++;
			SkyCraft.LOG.info("SkyCraft: underground: {} at {} (Minecraft y {})", state.getBlock().getName().getString(), pos.toShortString(), vy);
		}
	}

	/**
	 * What generation left in this cell of the hidden world comes up with it: the chest minecarts
	 * of mineshafts and the like (the hidden world has no players, so nothing else lives there).
	 */
	private static void bringUpEntities(ServerLevel hidden, ServerLevel level, BlockPos at, BlockPos pos) {
		List<Entity> found = hidden.getEntities((Entity) null, new AABB(at), e -> !(e instanceof Player) && e.isAlive() && e.blockPosition().equals(at));
		for (Entity e : found) {
			Vec3 to = e.position().add(0.0, pos.getY() - at.getY(), 0.0);
			Entity moved = e.teleport(new TeleportTransition(level, to, e.getDeltaMovement(), e.getYRot(), e.getXRot(), TeleportTransition.DO_NOTHING));
			if (moved != null) {
				SkyCraft.LOG.info("SkyCraft: underground: {} at {}", moved.getType().toShortString(), moved.blockPosition().toShortString());
			}
		}
	}

	private static void open(int world, BlockPos pos) {
		if (world != openingWorld) {
			OPENING.clear();
			WAITING.clear();
			openingWorld = world;
		}
		OPENING.enqueue(pos.asLong());
	}

	/** A cave opening up: its next cells, nearest first, while Skyrim says they're inside its ground. */
	private static void tick(MinecraftServer server) {
		ServerLevel level = server.overworld();
		if (!enabled || level == null) {
			return;
		}
		ServerLevel hidden = server.getLevel(UNDERGROUND);
		if (hidden == null) {
			return;
		}
		if (server.getTickCount() % 20 == 0 && SkyWorld.valid() && SkyWorld.tamriel()) {
			// The hidden world around the host is generated ahead of digging, in the background
			// (generating it on the spot stalled the game for seconds). Portal tickets lapse by
			// themselves once the player has moved on.
			for (var player : server.getPlayerList().getPlayers()) {
				if (player.level() == level && dev.skycraft.net.SkyNet.isHost(player)) {
					hidden.getChunkSource().addTicketWithRadius(TicketType.PORTAL, new ChunkPos(player.getBlockX() >> 4, player.getBlockZ() >> 4), AHEAD_CHUNKS);
				}
			}
		}
		for (var player : server.getPlayerList().getPlayers()) {
			if (player.level() == level && dev.skycraft.net.SkyNet.isHost(player)) {
				refocus(player.getX(), player.getEyeY(), player.getZ());
				if (deep(player.getX(), player.getEyeY(), player.getZ())) {
					heal(level, player, server.getTickCount());
				}
			}
		}
		SkyDig.beginBatch();
		try {
			reveal(level, hidden, server);
		} finally {
			SkyDig.endBatch();
		}
	}

	/**
	 * Revealed cells around the host still facing an unrevealed one deep in the ground (a reveal cut
	 * short: the game closed, or a save loaded, before it was done) open on: one chunk column a tick,
	 * round the 5 x 5 around the host. Left alone, they showed as holes out of the world.
	 */
	private static void heal(ServerLevel level, ServerPlayer host, int tick) {
		int world = SkyDig.worldFor(level);
		int k = Math.floorMod(tick, 25);
		int cx = (host.getBlockX() >> 4) + k % 5 - 2, cz = (host.getBlockZ() >> 4) + k / 5 - 2, sy = host.getBlockY() >> 4;
		LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
		SkyDig.DugColumn column = chunk == null ? null : chunk.getAttached(SkyDig.DUG);
		if (column == null) {
			return;
		}
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(), n = new BlockPos.MutableBlockPos();
		int healed = 0;
		for (int y = sy - 2; y <= sy + 2; y++) {
			long[] dug = column.bits(world, y);
			if (dug == null) {
				continue;
			}
			for (int word = 0; word < 64; word++) {
				long bits = dug[word];
				while (bits != 0) {
					int bit = word * 64 + Long.numberOfTrailingZeros(bits);
					bits &= bits - 1;
					pos.set(cx * 16 + (bit & 15), y * 16 + (bit >> 8), cz * 16 + ((bit >> 4) & 15));
					if (level.getBlockState(pos).isSolidRender()) {
						continue;
					}
					for (Direction d : Direction.values()) {
						n.setWithOffset(pos, d);
						if (level.isLoaded(n) && !SkyDig.isDug(level, world, n) && deep(n.getX() + 0.5, n.getY() + 0.5, n.getZ() + 0.5)) {
							open(world, pos.immutable());
							healed++;
							break;
						}
					}
				}
			}
		}
		if (healed > 0 && logged < 40) {
			logged++;
			SkyCraft.LOG.info("SkyCraft: underground: {} revealed cells in chunk {}, {} opened on (a reveal cut short)", healed, cx, cz);
		}
	}

	private static double distanceToFocus(long key) {
		double dx = BlockPos.getX(key) + 0.5 - focusX, dy = BlockPos.getY(key) + 0.5 - focusY, dz = BlockPos.getZ(key) + 0.5 - focusZ;
		return dx * dx + dy * dy + dz * dz;
	}

	/** The host moved on: the queue is ordered by distance to them again. */
	private static void refocus(double x, double y, double z) {
		double dx = x - focusX, dy = y - focusY, dz = z - focusZ;
		if (dx * dx + dy * dy + dz * dz < 16.0) {
			return;
		}
		long[] queued = new long[OPENING.size()];
		for (int i = 0; i < queued.length; i++) {
			queued[i] = OPENING.dequeueLong();
		}
		focusX = x;
		focusY = y;
		focusZ = z;
		OPENING = new it.unimi.dsi.fastutil.longs.LongHeapPriorityQueue(queued.length + 16, NEAREST);
		for (long key : queued) {
			OPENING.enqueue(key);
		}
	}

	/** One tick's revealing, within REVEAL_NANOS (the dug cells it marks go to the client once per chunk). */
	private static void reveal(ServerLevel level, ServerLevel hidden, MinecraftServer server) {
		long deadline = System.nanoTime() + REVEAL_NANOS;
		if (!FOR_CHUNK.isEmpty()) {
			// Cells dug where the hidden world wasn't ready: dig them now that it is.
			for (var it = FOR_CHUNK.entrySet().iterator(); it.hasNext();) {
				var entry = it.next();
				ChunkPos chunk = ChunkPos.unpack(entry.getKey());
				if (hidden.getChunkSource().getChunkNow(chunk.x(), chunk.z()) == null) {
					continue;
				}
				it.remove();
				for (Waiting w : entry.getValue()) {
					SkyDig.digCell(level, w.world(), w.pos(), w.material());
				}
			}
		}
		if (!WAITING.isEmpty() && server.getTickCount() % 20 == 0) {
			// Skyrim's shape may have arrived for these by now.
			for (long key : WAITING.toLongArray()) {
				BlockPos p = BlockPos.of(key);
				if (SkyCollision.isKnown(p.getX(), p.getY(), p.getZ()) || deep(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)) {
					WAITING.remove(key);
					OPENING.enqueue(key);
				}
			}
		}
		while (!OPENING.isEmpty() && System.nanoTime() < deadline) {
			BlockPos from = BlockPos.of(OPENING.dequeueLong());
			for (Direction d : Direction.values()) {
				BlockPos n = from.relative(d);
				if (!level.isLoaded(n)) {
					// Not loaded yet: later, not never (left undone, the cave ended in a hole out of the world).
					if (WAITING.size() < MAX_WAITING) {
						WAITING.add(from.asLong());
					}
					continue;
				}
				if (SkyDig.isDug(level, openingWorld, n)) {
					continue;
				}
				boolean deep = deep(n.getX() + 0.5, n.getY() + 0.5, n.getZ() + 0.5);
				if (!deep && !SkyCollision.isKnown(n.getX(), n.getY(), n.getZ())) {
					if (WAITING.size() < MAX_WAITING) {
						WAITING.add(from.asLong());
					}
					continue;
				}
				int material = deep ? Proto.DIG_STONE : SkyDig.classify(n.getX(), n.getY(), n.getZ());
				if (material <= SkyDig.AIR) {
					continue; // Skyrim's surface or a building is there: the cave stops at it
				}
				BlockState here = level.getBlockState(n);
				SkyDig.digCell(level, openingWorld, n, here.isAir() || here.canBeReplaced() ? material : 0);
			}
		}
	}
}
