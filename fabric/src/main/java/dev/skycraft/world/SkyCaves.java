// SkyCraft+ : the Minecraft underground under Skyrim
package dev.skycraft.world;

import dev.skycraft.SkyCraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.phys.AABB;

/**
 * Monsters in the dark of the real Minecraft underground. The mirror world is a void biome with
 * Skyrim's sky over everything, so vanilla spawning never fills its caves: this does, in the dug
 * and revealed cells around the host while they're under Skyrim's land, with the monsters of the
 * hidden world's biome at the matching spot (lush caves, dripstone caves, a swamp's witches...),
 * where no block light reaches (torches keep a mine safe, as in Minecraft). Settings in
 * config/skycraft.properties: caveMonsters, caveMonsterCap.
 */
final class SkyCaves {
	private static final int TRIES = 12;        // spots looked at each second
	private static final int MIN_DISTANCE = 12; // out of sight round a corner, mostly
	private static final int MAX_DISTANCE = 40;
	private static final int RANGE = 48;        // blocks around the host that count against the cap
	private static final int SLIME_LEVEL = 40;  // slimes: below this Minecraft y, in slime chunks

	private SkyCaves() {
	}

	/** One second's spawning around the host, if the host is down there. */
	static void tick(ServerLevel level, ServerPlayer host, RandomSource random, int cap) {
		if (level.dimension() != Level.OVERWORLD || !SkyUnderground.deep(host.getX(), host.getEyeY(), host.getZ())) {
			return;
		}
		ServerLevel hidden = level.getServer().getLevel(SkyUnderground.UNDERGROUND);
		if (hidden == null) {
			return;
		}
		AABB box = host.getBoundingBox().inflate(RANGE);
		if (level.getEntitiesOfClass(Mob.class, box, mob -> mob.isAlive() && mob.getType().getCategory() == MobCategory.MONSTER).size() >= cap) {
			return;
		}
		int world = SkyDig.worldFor(level);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < TRIES; i++) {
			double angle = random.nextDouble() * Math.PI * 2.0;
			double distance = MIN_DISTANCE + random.nextDouble() * (MAX_DISTANCE - MIN_DISTANCE);
			int x = (int) Math.floor(host.getX() + Math.cos(angle) * distance);
			int z = (int) Math.floor(host.getZ() + Math.sin(angle) * distance);
			int top = host.getBlockY() + random.nextInt(25) - 12 + 8;
			for (int y = top; y >= top - 16; y--) {
				pos.set(x, y, z);
				if (!level.isLoaded(pos)) {
					break;
				}
				if (spot(level, world, pos)) {
					spawnGroup(level, hidden, world, pos.immutable(), random);
					return;
				}
			}
		}
	}

	/** Room for a monster in a revealed cave: two dug, empty, dark cells over a real block, under Skyrim's land. */
	private static boolean spot(ServerLevel level, int world, BlockPos pos) {
		BlockPos above = pos.above(), below = pos.below();
		if (!level.getBlockState(pos).isAir() || !level.getBlockState(above).isAir()) {
			return false;
		}
		BlockState floor = level.getBlockState(below);
		if (!floor.isFaceSturdy(level, below, Direction.UP)) {
			return false;
		}
		if (!SkyDig.isDug(level, world, pos) || !SkyDig.isDug(level, world, above) || level.getBrightness(LightLayer.BLOCK, pos) > 0) {
			return false;
		}
		return SkyUnderground.deep(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
	}

	private static void spawnGroup(ServerLevel level, ServerLevel hidden, int world, BlockPos pos, RandomSource random) {
		if (!SkyUnderground.ready(level, pos)) {
			return;
		}
		BlockPos at = new BlockPos(pos.getX(), pos.getY() + SkyUnderground.shiftAt(level, pos), pos.getZ());
		var mobs = hidden.getChunkSource().getGenerator().getMobsAt(hidden, hidden.structureManager(), MobCategory.MONSTER, at);
		MobSpawnSettings.SpawnerData data = mobs.getRandom(random).orElse(null);
		if (data == null) {
			return; // the deep dark: nothing spawns there
		}
		EntityType<?> type = data.type();
		if (type == EntityTypes.SLIME) {
			boolean slimeChunk = WorldgenRandom.seedSlimeChunk(at.getX() >> 4, at.getZ() >> 4, hidden.getSeed(), 987234911L).nextInt(10) == 0;
			if (!slimeChunk || at.getY() >= SLIME_LEVEL || random.nextInt(10) != 0) {
				return;
			}
		}
		int count = Math.max(1, data.count().sample(random));
		int spawned = 0;
		for (int i = 0; i < count; i++) {
			BlockPos p = i == 0 ? pos : pos.offset(random.nextInt(5) - 2, 0, random.nextInt(5) - 2);
			if (i > 0 && (!level.isLoaded(p) || !spot(level, world, p))) {
				continue;
			}
			if (!SpawnPlacements.isSpawnPositionOk(type, level, p)) {
				continue;
			}
			Entity entity = type.create(level, EntitySpawnReason.NATURAL);
			if (!(entity instanceof Mob mob)) {
				if (entity != null) {
					entity.discard();
				}
				return;
			}
			mob.snapTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, random.nextFloat() * 360.0F, 0.0F);
			if (!level.noCollision(mob)) {
				mob.discard();
				continue;
			}
			mob.finalizeSpawn(level, level.getCurrentDifficultyAt(p), EntitySpawnReason.NATURAL, null);
			level.addFreshEntityWithPassengers(mob);
			spawned++;
		}
		if (spawned > 0 && SkyCraft.LOG.isDebugEnabled()) {
			SkyCraft.LOG.debug("SkyCraft: underground: {} x{} at {}", type.toShortString(), spawned, pos.toShortString());
		}
	}
}
