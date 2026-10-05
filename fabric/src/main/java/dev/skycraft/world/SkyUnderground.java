// SkyCraft+ : the Minecraft underground under Skyrim
package dev.skycraft.world;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Real Minecraft depth under Skyrim's open world. A hidden dimension, skycraft:underground, is a
 * plain vanilla overworld (vanilla's own generator, biomes and structures, nothing changed). Where
 * digging reaches into Skyrim's ground, the block at the matching spot of that world is brought up
 * into the mirror world: the two line up so Skyrim's sea level is Minecraft's (63) and bedrock is at
 * Minecraft's -64 below it. Where that world has a cave (air, water or lava), the cell opens and the
 * cave is revealed outwards, a few hundred cells a tick, only inside Skyrim's ground (cells Skyrim's
 * shape says are inside it; regions it hasn't sent yet wait). Above vanilla's own terrain but under a
 * Skyrim mountain is stone. Setting: deepUnderground in config/skycraft.properties (off: upstream's
 * dirt, stone and bedrock five blocks down).
 */
public final class SkyUnderground {
	public static final ResourceKey<Level> UNDERGROUND = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "underground"));
	private static final int VANILLA_SEA = 63;
	private static final int VANILLA_BOTTOM = -64;
	private static final int SURFACE_SKIN = 5;     // vanilla's own surface layers (grass, dirt, sand, sea floor) count as stone
	private static final int CELLS_PER_TICK = 128;
	private static final int MAX_WAITING = 20000;

	private static boolean enabled = true;

	/** Cells whose neighbours are to be revealed (a cave opening up), for one Skyrim world. */
	private static final LongArrayFIFOQueue OPENING = new LongArrayFIFOQueue();
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
		SkyCraft.LOG.info("SkyCraft: Minecraft's underground under Skyrim {}", enabled ? "on" : "off");
	}

	/** This dug cell of Skyrim's ground (a Proto.DIG_* material) comes from the hidden world. */
	public static boolean takes(ServerLevel level, int material) {
		return enabled && level.dimension() == Level.OVERWORLD && level.getServer().getLevel(UNDERGROUND) != null
			&& (material == Proto.DIG_STONE || material == Proto.DIG_NONE || material == Proto.DIG_BEDROCK);
	}

	/** Mirror y minus this is the hidden world's y. */
	private static int offset() {
		return Math.round(SkyWorld.seaLevel()) - VANILLA_SEA;
	}

	/** Brings the hidden world's block for this (already dug) cell up into the mirror world. */
	public static void bringUp(ServerLevel level, int world, BlockPos pos) {
		ServerLevel hidden = level.getServer().getLevel(UNDERGROUND);
		if (hidden == null) {
			return;
		}
		int vy = pos.getY() - offset();
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
		if (logged < 20 && !state.is(Blocks.STONE) && !state.is(Blocks.DEEPSLATE)) {
			logged++;
			SkyCraft.LOG.info("SkyCraft: underground: {} at {} (Minecraft y {})", state.getBlock().getName().getString(), pos.toShortString(), vy);
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
		if (!WAITING.isEmpty() && server.getTickCount() % 20 == 0) {
			// Skyrim's shape may have arrived for these by now.
			for (long key : WAITING.toLongArray()) {
				BlockPos p = BlockPos.of(key);
				if (SkyCollision.isKnown(p.getX(), p.getY(), p.getZ())) {
					WAITING.remove(key);
					OPENING.enqueue(key);
				}
			}
		}
		int budget = CELLS_PER_TICK;
		while (!OPENING.isEmpty() && budget > 0) {
			BlockPos from = BlockPos.of(OPENING.dequeueLong());
			for (Direction d : Direction.values()) {
				BlockPos n = from.relative(d);
				if (!level.isLoaded(n) || SkyDig.isDug(level, openingWorld, n)) {
					continue;
				}
				budget--;
				if (!SkyCollision.isKnown(n.getX(), n.getY(), n.getZ())) {
					if (WAITING.size() < MAX_WAITING) {
						WAITING.add(from.asLong());
					}
					continue;
				}
				int material = SkyDig.classify(n.getX(), n.getY(), n.getZ());
				if (material <= SkyDig.AIR) {
					continue; // Skyrim's surface or a building is there: the cave stops at it
				}
				BlockState here = level.getBlockState(n);
				SkyDig.digCell(level, openingWorld, n, here.isAir() || here.canBeReplaced() ? material : 0);
			}
		}
	}
}
