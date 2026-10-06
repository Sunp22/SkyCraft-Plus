// SkyCraft+ : Minecraft's structures in Skyrim's world
package dev.skycraft.world;

import dev.skycraft.SkyCraft;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft's surface structures in Skyrim's open world, at fixed spots (SkyrimMap: read from the
 * player's Skyrim.esm, the same for everyone): villages of every kind, pillager outposts, the
 * woodland mansion, the temples, the witch hut, the igloo, ruined portals, trail ruins, buried
 * treasure, wrecks, ocean ruins and the ocean monument. Each is built the first time the host comes
 * near its spot, by Minecraft's own generator, on Skyrim's ground: the mirror world has no terrain,
 * so while one is laid out and placed, the ground it sees is Skyrim's land there. Villages come with
 * their villagers and golems, the mansion with its illagers, the hut with its witch; outposts, huts
 * and monuments keep spawning their own (pillagers, witches, guardians) while the host is near.
 * Underground ones (strongholds, mineshafts, trial chambers, ancient cities) come up with the
 * underground (SkyUnderground). Setting: skyrimStructures in config/skycraft.properties.
 */
public final class SkyStructures {
	private static final double BUILD_DISTANCE = 160.0;  // blocks: built when the host comes this near
	private static final double SPAWN_DISTANCE = 64.0;   // their own mobs keep coming while the host is this near
	private static final int MOBS_PER_STRUCTURE = 4;

	private static boolean enabled = true;
	private static volatile @Nullable SkyrimMap map;
	private static volatile boolean reading;
	/** Built in this world: site name -> its bounding box (and structure, for its own mobs). */
	private static final Map<String, Built> BUILT = new HashMap<>();
	private static @Nullable Path builtFile;

	/** Set while a structure is laid out and placed: the ground Level.getHeight reports (LevelHeightMixin). */
	public static volatile @Nullable SkyrimMap placing;

	private record Built(String structure, BoundingBox box) {
	}

	/** A structure laid out and being placed a few chunks a tick (a village at once stalled the game a second). */
	private record Pending(SkyrimMap.Site site, StructureStart start, FlatLevelSource ground, BoundingBox box, List<ChunkPos> chunks, long started) {
	}

	private static @Nullable Pending pending;
	private static int pendingNext;
	private static final long PLACE_NANOS = 15_000_000L;  // a tick's share of placing

	private SkyStructures() {
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(SkyStructures::start);
		ServerTickEvents.END_SERVER_TICK.register(SkyStructures::tick);
	}

	private static void start(MinecraftServer server) {
		Properties p = new Properties();
		Path config = FabricLoader.getInstance().getConfigDir().resolve("skycraft.properties");
		if (Files.exists(config)) {
			try (Reader in = Files.newBufferedReader(config)) {
				p.load(in);
			} catch (IOException e) {
				SkyCraft.LOG.warn("SkyCraft: couldn't read {}: {}", config, e.toString());
			}
		}
		enabled = Boolean.parseBoolean(p.getProperty("skyrimStructures", "true").trim());
		BUILT.clear();
		pending = null;
		pendingNext = 0;
		builtFile = server.getWorldPath(LevelResource.ROOT).resolve("skycraft-structures.txt");
		loadBuilt();
		if (!enabled || map != null || reading) {
			return;
		}
		Path esm = skyrimEsm();
		if (esm == null) {
			SkyCraft.LOG.info("SkyCraft: Minecraft's structures in Skyrim: Skyrim's Data folder isn't known yet (start Skyrim with SkyCraft once)");
			return;
		}
		reading = true;
		Thread thread = new Thread(() -> {
			try {
				long t0 = System.nanoTime();
				SkyrimMap read = SkyrimMap.read(esm);
				map = read;
				SkyCraft.LOG.info("SkyCraft: Minecraft's structures in Skyrim: {} spots from {} in {} s", read.sites().size(), esm,
					String.format("%.1f", (System.nanoTime() - t0) / 1e9));
				for (SkyrimMap.Site s : read.sites()) {
					SkyCraft.LOG.info("SkyCraft:   {} ({}) at {} {} {}", s.name(), s.structure(), Math.round(s.x()), Math.round(s.groundY()), Math.round(s.z()));
				}
			} catch (IOException | RuntimeException e) {
				SkyCraft.LOG.warn("SkyCraft: couldn't read Skyrim's land from {}", esm, e);
			} finally {
				reading = false;
			}
		}, "SkyCraft Skyrim map");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		thread.start();
	}

	/** Skyrim.esm, from the Data folder the SKSE plugin noted (%LOCALAPPDATA%\SkyCraft\skyrim-data.txt). */
	private static @Nullable Path skyrimEsm() {
		String local = System.getenv("LOCALAPPDATA");
		if (local == null) {
			return null;
		}
		Path note = Path.of(local, "SkyCraft", "skyrim-data.txt");
		try {
			if (!Files.exists(note)) {
				return null;
			}
			Path esm = Path.of(Files.readString(note).trim()).resolve("Skyrim.esm");
			return Files.exists(esm) ? esm : null;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private static void loadBuilt() {
		if (builtFile == null || !Files.exists(builtFile)) {
			return;
		}
		try {
			for (String line : Files.readAllLines(builtFile)) {
				String[] f = line.split("\\|");
				if (f.length == 8) {
					BUILT.put(f[0], new Built(f[1], new BoundingBox(Integer.parseInt(f[2]), Integer.parseInt(f[3]), Integer.parseInt(f[4]), Integer.parseInt(f[5]),
						Integer.parseInt(f[6]), Integer.parseInt(f[7]))));
				}
			}
		} catch (IOException | RuntimeException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't read {}", builtFile, e);
		}
	}

	private static void saveBuilt() {
		if (builtFile == null) {
			return;
		}
		List<String> lines = new ArrayList<>();
		BUILT.forEach((name, b) -> lines.add(name + "|" + b.structure + "|" + b.box.minX() + "|" + b.box.minY() + "|" + b.box.minZ() + "|" + b.box.maxX() + "|"
			+ b.box.maxY() + "|" + b.box.maxZ()));
		try {
			Files.write(builtFile, lines);
		} catch (IOException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't save {}", builtFile, e);
		}
	}

	/** Development: SKYCRAFT_BUILD_ALL_STRUCTURES=1 builds every spot at once, with no Skyrim or player. */
	private static final boolean BUILD_ALL = "1".equals(System.getenv("SKYCRAFT_BUILD_ALL_STRUCTURES"));

	private static void tick(MinecraftServer server) {
		SkyrimMap m = map;
		if (pending != null && m != null) {
			continueBuilding(server.overworld(), m);
			return;
		}
		if (BUILD_ALL && enabled && m != null) {
			for (SkyrimMap.Site site : m.sites()) {
				if (!BUILT.containsKey(site.name())) {
					build(server.overworld(), m, site);
					return;
				}
			}
			return;
		}
		if (!enabled || m == null || server.getTickCount() % 20 != 7 || !SkyWorld.valid() || !SkyWorld.tamriel()) {
			return;
		}
		ServerLevel level = server.overworld();
		ServerPlayer host = null;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.level() == level && dev.skycraft.net.SkyNet.isHost(player)) {
				host = player;
			}
		}
		if (host == null) {
			return;
		}
		for (SkyrimMap.Site site : m.sites()) {
			double dx = site.x() - host.getX(), dz = site.z() - host.getZ();
			double distance = Math.sqrt(dx * dx + dz * dz);
			if (!BUILT.containsKey(site.name()) && distance < BUILD_DISTANCE && !Double.isNaN(site.groundY()) && pending == null) {
				build(level, m, site);
				return;  // one a second at most
			}
		}
		if (server.getTickCount() % 300 == 7) {
			spawnTheirOwn(level, host);
		}
	}

	/** Builds one structure at its spot, as /place structure would, on Skyrim's ground. */
	private static void build(ServerLevel level, SkyrimMap m, SkyrimMap.Site site) {
		var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
		Optional<Holder.Reference<Structure>> holder = registry.get(ResourceKey.create(Registries.STRUCTURE, Identifier.parse(site.structure())));
		if (holder.isEmpty() || !(level.getChunkSource().getGenerator() instanceof FlatLevelSource flat)) {
			BUILT.put(site.name(), new Built(site.structure(), BoundingBox.fromCorners(BlockPos.ZERO, BlockPos.ZERO)));
			saveBuilt();
			return;
		}
		// Laid out at an ordinary Minecraft height (vanilla's structures want ground above sea level,
		// the mansion above y 60), then moved down to Skyrim's ground.
		int shift = 80 - (int) Math.floor(site.groundY());
		boolean ocean = site.structure().contains("ocean") || site.structure().contains("monument") || site.structure().contains("shipwreck");
		Holder<Biome> biome = level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(biomeFor(site.structure()));
		Ground ground = new Ground(flat.settings().withBiomeAndLayers(List.of(), Optional.empty(), biome), m, shift, ocean);
		BlockPos at = BlockPos.containing(site.x(), site.groundY(), site.z());
		long t0 = System.nanoTime();
		StructureStart start;
		placing = m;
		try {
			start = holder.get().value().generate(holder.get(), level.dimension(), level.registryAccess(), ground, ground.getBiomeSource(),
				level.getChunkSource().randomState().createClimateSampler(net.minecraft.world.level.levelgen.densityfunction.SamplerContext.EMPTY_UNCACHED),
				level.getChunkSource().randomState(), level.getStructureTemplateManager(), level.getSeed() ^ site.name().hashCode(), ChunkPos.containing(at), 0, level,
				b -> true);
			if (!start.isValid()) {
				SkyCraft.LOG.warn("SkyCraft: {} ({}) couldn't be laid out at {}", site.name(), site.structure(), at.toShortString());
				BUILT.put(site.name(), new Built(site.structure(), BoundingBox.fromCorners(at, at)));
				saveBuilt();
				return;
			}
			for (StructurePiece piece : start.getPieces()) {
				piece.move(0, -shift, 0);
			}
			BoundingBox box = start.getBoundingBox();
			ChunkPos min = new ChunkPos(SectionPos.blockToSectionCoord(box.minX()), SectionPos.blockToSectionCoord(box.minZ()));
			ChunkPos max = new ChunkPos(SectionPos.blockToSectionCoord(box.maxX()), SectionPos.blockToSectionCoord(box.maxZ()));
			// Its neighbours too: blocks placed look round them (a pumpkin for a golem to make). Kept
			// loaded while it's placed over the next ticks.
			int reach = (Math.max(max.x() - min.x(), max.z() - min.z()) + 1) / 2 + 1;
			level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.PORTAL,
				new ChunkPos((min.x() + max.x()) / 2, (min.z() + max.z()) / 2), Math.min(reach, 8));
			for (int cx = min.x() - 1; cx <= max.x() + 1; cx++) {
				for (int cz = min.z() - 1; cz <= max.z() + 1; cz++) {
					level.getChunk(cx, cz);  // the mirror world's chunks are empty: cheap to have loaded
				}
			}
			List<ChunkPos> chunks = new ArrayList<>();
			for (int cx = min.x(); cx <= max.x(); cx++) {
				for (int cz = min.z(); cz <= max.z(); cz++) {
					chunks.add(new ChunkPos(cx, cz));
				}
			}
			pending = new Pending(site, start, ground, box, chunks, t0);
		} catch (RuntimeException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't build {} ({})", site.name(), site.structure(), e);
			BUILT.put(site.name(), new Built(site.structure(), BoundingBox.fromCorners(at, at)));
			saveBuilt();
		} finally {
			placing = null;
		}
	}

	/** Places the pending structure's next chunks, as many as PLACE_NANOS allows; done: it's recorded. */
	private static void continueBuilding(ServerLevel level, SkyrimMap m) {
		Pending job = pending;
		long deadline = System.nanoTime() + PLACE_NANOS;
		placing = m;
		try {
			while (pendingNext < job.chunks().size() && System.nanoTime() < deadline) {
				ChunkPos c = job.chunks().get(pendingNext++);
				job.start().placeInChunk(level, level.structureManager(), job.ground(), level.getRandom(),
					new BoundingBox(c.getMinBlockX(), level.getMinY(), c.getMinBlockZ(), c.getMaxBlockX(), level.getMaxY() + 1, c.getMaxBlockZ()), c);
			}
		} catch (RuntimeException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't finish building {} ({})", job.site().name(), job.site().structure(), e);
			pendingNext = job.chunks().size();
		} finally {
			placing = null;
		}
		if (pendingNext < job.chunks().size()) {
			return;
		}
		pending = null;
		pendingNext = 0;
		BUILT.put(job.site().name(), new Built(job.site().structure(), job.box()));
		saveBuilt();
		var area = new net.minecraft.world.phys.AABB(job.box().minX(), job.box().minY(), job.box().minZ(), job.box().maxX() + 1, job.box().maxY() + 1, job.box().maxZ() + 1);
		int mobs = level.getEntitiesOfClass(Mob.class, area, Mob::isAlive).size();
		SkyCraft.LOG.info("SkyCraft: built {} ({}) at {} in {} ms ({} chunks, {} mobs with it)", job.site().name(), job.site().structure(),
			job.box().getCenter().toShortString(), (System.nanoTime() - job.started()) / 1_000_000, job.chunks().size(), mobs);
		if (!BUILD_ALL) {
			String what = job.site().name().replaceAll(" [0-9]+$", "");
			level.getServer().getPlayerList().broadcastSystemMessage(net.minecraft.network.chat.Component.literal("A " + what.toLowerCase(java.util.Locale.ROOT) + " stands nearby."), false);
		}
	}

	private static ResourceKey<Biome> biomeFor(String structure) {
		if (structure.contains("monument") || structure.contains("ocean_ruin")) {
			return Biomes.DEEP_COLD_OCEAN;
		}
		if (structure.contains("shipwreck") || structure.contains("buried_treasure")) {
			return Biomes.BEACH;
		}
		if (structure.contains("snowy") || structure.contains("igloo")) {
			return Biomes.SNOWY_PLAINS;
		}
		if (structure.contains("desert")) {
			return Biomes.DESERT;
		}
		if (structure.contains("savanna")) {
			return Biomes.SAVANNA;
		}
		if (structure.contains("taiga")) {
			return Biomes.TAIGA;
		}
		if (structure.contains("jungle")) {
			return Biomes.JUNGLE;
		}
		if (structure.contains("swamp")) {
			return Biomes.SWAMP;
		}
		if (structure.contains("mansion")) {
			return Biomes.DARK_FOREST;
		}
		return Biomes.PLAINS;
	}

	/**
	 * Outposts, huts and monuments keep spawning their own (pillagers, witches, guardians) as in
	 * Minecraft, which spawns these by structure: mob spawning is off in the mirror world (SkySpawner
	 * fills Skyrim's), so here they're brought in near the host now and then, inside the structure.
	 */
	private static void spawnTheirOwn(ServerLevel level, ServerPlayer host) {
		RandomSource random = level.getRandom();
		for (Built built : BUILT.values()) {
			BoundingBox box = built.box;
			if (box.getXSpan() < 2 || host.blockPosition().distManhattan(box.getCenter()) > SPAWN_DISTANCE * 2) {
				continue;
			}
			var holder = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).get(ResourceKey.create(Registries.STRUCTURE, Identifier.parse(built.structure)));
			if (holder.isEmpty()) {
				continue;
			}
			var override = holder.get().value().spawnOverrides().get(MobCategory.MONSTER);
			if (override == null || override.spawns().isEmpty()) {
				continue;
			}
			var area = new net.minecraft.world.phys.AABB(box.minX(), box.minY(), box.minZ(), box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1).inflate(8.0);
			if (level.getEntitiesOfClass(Mob.class, area, mob -> mob.isAlive() && mob.getType().getCategory() == MobCategory.MONSTER).size() >= MOBS_PER_STRUCTURE) {
				continue;
			}
			MobSpawnSettings.SpawnerData data = override.spawns().getRandom(random).orElse(null);
			if (data == null) {
				continue;
			}
			for (int tries = 0; tries < 12; tries++) {
				int x = box.minX() + random.nextInt(box.getXSpan()), z = box.minZ() + random.nextInt(box.getZSpan());
				for (int y = box.maxY(); y >= box.minY(); y--) {
					BlockPos pos = new BlockPos(x, y, z);
					if (!SpawnPlacements.isSpawnPositionOk(data.type(), level, pos) || !level.noCollision(data.type().getSpawnAABB(x + 0.5, y, z + 0.5))) {
						continue;
					}
					Entity entity = data.type().create(level, EntitySpawnReason.STRUCTURE);
					if (entity instanceof Mob mob) {
						mob.snapTo(x + 0.5, y, z + 0.5, random.nextFloat() * 360.0F, 0.0F);
						mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), EntitySpawnReason.STRUCTURE, null);
						level.addFreshEntityWithPassengers(mob);
					} else if (entity != null) {
						entity.discard();
					}
					tries = 99;
					break;
				}
			}
		}
	}

	/** The ground near a spot (Minecraft y), as Level.getHeight reports it while placing (LevelHeightMixin). */
	public static int heightWhilePlacing(SkyrimMap m, Heightmap.Types type, int x, int z, int vanilla) {
		double ground = m.groundAt(x + 0.5, z + 0.5);
		if (Double.isNaN(ground)) {
			return vanilla;
		}
		double top = ground;
		if (type != Heightmap.Types.OCEAN_FLOOR && type != Heightmap.Types.OCEAN_FLOOR_WG) {
			top = Math.max(top, m.waterAt(x + 0.5, z + 0.5));
		}
		return Math.max(vanilla, (int) Math.round(top));
	}

	/**
	 * The mirror world's generator, but its ground is Skyrim's land near a spot (shifted up by
	 * {@code shift}, so vanilla's structures find it at an ordinary height), with water up to
	 * Skyrim's where there is some.
	 */
	private static final class Ground extends FlatLevelSource {
		private final SkyrimMap m;
		private final int shift;
		private final boolean ocean;

		Ground(FlatLevelGeneratorSettings settings, SkyrimMap m, int shift, boolean ocean) {
			super(settings);
			this.m = m;
			this.shift = shift;
			this.ocean = ocean;
		}

		private double ground(int x, int z) {
			double g = this.m.groundAt(x + 0.5, z + 0.5);
			return Double.isNaN(g) ? 64 - this.shift : g;
		}

		private double water(int x, int z) {
			return this.m.waterAt(x + 0.5, z + 0.5);
		}

		@Override
		public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor heightAccessor, RandomState randomState) {
			double top = this.ground(x, z);
			if (type != Heightmap.Types.OCEAN_FLOOR && type != Heightmap.Types.OCEAN_FLOOR_WG) {
				top = Math.max(top, this.water(x, z));
			}
			return (int) Math.round(top) + this.shift + 1;
		}

		@Override
		public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor heightAccessor, RandomState randomState) {
			int minY = heightAccessor.getMinY();
			BlockState[] column = new BlockState[heightAccessor.getHeight()];
			int groundTop = (int) Math.round(this.ground(x, z)) + this.shift;
			int waterTop = (int) Math.round(this.water(x, z)) + this.shift;
			for (int i = 0; i < column.length; i++) {
				int y = minY + i;
				column[i] = y <= groundTop ? Blocks.STONE.defaultBlockState() : y <= waterTop ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
			}
			return new NoiseColumn(minY, column);
		}

		@Override
		public int getSeaLevel() {
			// Above water: as low as can be, so nothing counts as under the sea; at sea: Skyrim's.
			return this.ocean ? (int) Math.round(this.m.seaLevel()) + this.shift : -1000;
		}

		@Override
		public net.minecraft.world.level.biome.BiomeSource getBiomeSource() {
			return super.getBiomeSource();
		}
	}
}
