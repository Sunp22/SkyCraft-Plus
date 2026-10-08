// SkyCraft+ : interiors kept apart
package dev.skycraft.world;

import dev.skycraft.SkyCraft;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * Skyrim's interiors share one Minecraft dimension (skycraft:interiors) at their own coordinates,
 * which overlap: blocks built in one house showed up in another. The dimension holds only what
 * players build, so it holds one interior's builds at a time: the chunks blocks were set in while in
 * an interior are noted; going into another interior, they're saved (blocks and block entities:
 * chest contents and the like) under the one left and cleared, and the one entered gets its own back.
 * Saved in the world folder (skycraft-interiors/). Builds made before this stay where they are.
 * Setting: separateInteriors in config/skycraft.properties.
 */
public final class SkyInteriors {
	public static final ResourceKey<Level> INTERIORS = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "interiors"));

	private static boolean enabled = true;
	/** The Skyrim interior whose builds the dimension holds now (0: none yet). */
	private static int current;
	/** Chunks blocks were set in since: what belongs to the current interior. */
	private static final LongOpenHashSet TOUCHED = new LongOpenHashSet();
	private static @Nullable Path folder;
	private static boolean swapping;

	private SkyInteriors() {
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(SkyInteriors::start);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> saveState());
		ServerTickEvents.END_SERVER_TICK.register(SkyInteriors::tick);
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
		enabled = Boolean.parseBoolean(p.getProperty("separateInteriors", "true").trim());
		folder = server.getWorldPath(LevelResource.ROOT).resolve("skycraft-interiors");
		current = 0;
		TOUCHED.clear();
		Path state = folder.resolve("current.txt");
		try {
			if (Files.exists(state)) {
				List<String> lines = Files.readAllLines(state);
				if (!lines.isEmpty()) {
					current = (int) Long.parseLong(lines.get(0).trim(), 16);
					for (int i = 1; i < lines.size(); i++) {
						TOUCHED.add(Long.parseLong(lines.get(i).trim()));
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't read {}", state, e);
		}
	}

	private static void saveState() {
		if (folder == null) {
			return;
		}
		List<String> lines = new ArrayList<>();
		lines.add(Integer.toHexString(current));
		for (long chunk : TOUCHED) {
			lines.add(Long.toString(chunk));
		}
		try {
			Files.createDirectories(folder);
			Path temp = folder.resolve("current.txt.tmp");  // whole or not at all
			Files.write(temp, lines);
			Files.move(temp, folder.resolve("current.txt"), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't save interiors state", e);
		}
	}

	/** Level.setBlock in the interiors dimension (LevelSetBlockMixin): that chunk holds the current interior's builds. */
	public static void touched(Level level, BlockPos pos) {
		if (enabled && !level.isClientSide() && level.dimension() == INTERIORS) {
			TOUCHED.add(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4));
		}
	}

	private static void tick(MinecraftServer server) {
		if (!enabled || server.getTickCount() % 10 != 3 || !SkyWorld.valid() || !SkyWorld.interior()) {
			return;
		}
		ServerLevel interiors = server.getLevel(INTERIORS);
		if (interiors == null) {
			return;
		}
		int world = SkyDig.worldFor(interiors);
		if (world == 0 || world == current) {
			return;
		}
		swap(interiors, world);
	}

	/** Into another interior: the one left's builds are saved and cleared, the one entered gets its own back. */
	private static void swap(ServerLevel level, int into) {
		swapping = true;
		try {
			int out = current;
			int saved = 0;
			if (out != 0 && !TOUCHED.isEmpty()) {
				saved = saveAndClear(level, out);
				if (saved < 0) {
					return;  // couldn't save: nothing cleared, try again later
				}
			}
			TOUCHED.clear();
			int restored = restore(level, into);
			current = into;
			saveState();
			SkyCraft.LOG.info("SkyCraft: interior {} -> {}: {} blocks put away, {} brought back", Integer.toHexString(out), Integer.toHexString(into), saved,
				restored);
		} finally {
			swapping = false;
		}
	}

	private static Path fileFor(int interior) {
		return folder.resolve(Integer.toHexString(interior) + ".dat");
	}

	/** Saves every block of the touched chunks under this interior, then clears them. -1: not saved. */
	private static int saveAndClear(ServerLevel level, int interior) {
		ListTag blocks = new ListTag();
		List<BlockPos> clear = new ArrayList<>();
		for (long packed : TOUCHED) {
			ChunkPos cp = ChunkPos.unpack(packed);
			LevelChunk chunk = level.getChunk(cp.x(), cp.z());
			LevelChunkSection[] sections = chunk.getSections();
			for (int i = 0; i < sections.length; i++) {
				if (sections[i].hasOnlyAir()) {
					continue;
				}
				int baseY = level.getSectionYFromSectionIndex(i) << 4;
				for (int y = 0; y < 16; y++) {
					for (int z = 0; z < 16; z++) {
						for (int x = 0; x < 16; x++) {
							BlockState state = sections[i].getBlockState(x, y, z);
							if (state.isAir()) {
								continue;
							}
							BlockPos pos = new BlockPos(cp.getMinBlockX() + x, baseY + y, cp.getMinBlockZ() + z);
							CompoundTag tag = new CompoundTag();
							tag.putInt("x", pos.getX());
							tag.putInt("y", pos.getY());
							tag.putInt("z", pos.getZ());
							tag.put("state", NbtUtils.writeBlockState(state));
							BlockEntity entity = chunk.getBlockEntity(pos);
							if (entity != null) {
								tag.put("entity", entity.saveWithFullMetadata(level.registryAccess()));
							}
							blocks.add(tag);
							clear.add(pos);
						}
					}
				}
			}
		}
		CompoundTag root = new CompoundTag();
		root.put("blocks", blocks);
		try {
			Files.createDirectories(folder);
			Path file = fileFor(interior);
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");  // whole or not at all
			NbtIo.writeCompressed(root, temp);
			Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't save interior {}'s builds; they stay", Integer.toHexString(interior), e);
			return -1;
		}
		int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_SKIP_ON_PLACE;
		for (BlockPos pos : clear) {
			level.removeBlockEntity(pos);  // (a chest's contents went with it into the file, not onto the floor)
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), flags, 0);
		}
		return clear.size();
	}

	/** Puts back what was saved for this interior; its chunks are the touched ones from now on. */
	private static int restore(ServerLevel level, int interior) {
		Path file = fileFor(interior);
		if (!Files.exists(file)) {
			return 0;
		}
		CompoundTag root;
		try {
			root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
		} catch (IOException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't read interior {}'s builds", Integer.toHexString(interior), e);
			return 0;
		}
		var blocksLookup = level.registryAccess().lookupOrThrow(Registries.BLOCK);
		int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE;
		int count = 0;
		for (Tag t : root.getListOrEmpty("blocks")) {
			if (!(t instanceof CompoundTag tag)) {
				continue;
			}
			BlockPos pos = new BlockPos(tag.getIntOr("x", 0), tag.getIntOr("y", 0), tag.getIntOr("z", 0));
			BlockState state = NbtUtils.readBlockState(blocksLookup, tag.getCompoundOrEmpty("state"));
			level.setBlock(pos, state, flags, 0);
			TOUCHED.add(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4));
			if (tag.contains("entity")) {
				BlockEntity entity = BlockEntity.loadStatic(pos, state, tag.getCompoundOrEmpty("entity"), level.registryAccess());
				if (entity != null) {
					level.setBlockEntity(entity);
				}
			}
			count++;
		}
		return count;
	}

	/** While builds are being put away or back, their own block changes aren't noted. */
	public static boolean swapping() {
		return swapping;
	}
}
