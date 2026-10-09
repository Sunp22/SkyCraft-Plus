// SkyCraft+ : strongholds kept from under Skyrim's water
package dev.skycraft.mixin;

import dev.skycraft.SkyCraft;
import dev.skycraft.world.SkyStructures;
import dev.skycraft.world.SkyrimMap;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Strongholds (the only structures placed in rings; only the underground's hidden world has them) go
 * under dry land: Skyrim's water reaches all the way down, and one under a lake or the sea flooded
 * with water no block could hold back. Eyes of ender ask the same positions, so they lead there.
 */
@Mixin(ChunkGeneratorStructureState.class)
public abstract class StrongholdPlacementMixin {
	@Unique
	private static final Executor SKYCRAFT$WAITER = task -> {
		Thread thread = new Thread(task, "SkyCraft strongholds");
		thread.setDaemon(true);
		thread.start();
	};

	@Inject(method = "generateRingPositions", at = @At("RETURN"), cancellable = true)
	private void skycraft$offWater(Holder<StructureSet> set, ConcentricRingsStructurePlacement placement, CallbackInfoReturnable<CompletableFuture<List<ChunkPos>>> cir) {
		// Skyrim's map is read once the server has started: waited for on a thread of its own.
		cir.setReturnValue(cir.getReturnValue().thenApplyAsync(StrongholdPlacementMixin::skycraft$move, SKYCRAFT$WAITER));
	}

	@Unique
	private static List<ChunkPos> skycraft$move(List<ChunkPos> positions) {
		SkyrimMap map = SkyStructures.awaitMap(90_000L);
		if (map == null || positions.isEmpty()) {
			if (!positions.isEmpty()) {
				SkyCraft.LOG.info("SkyCraft: strongholds where Minecraft put them (no Skyrim map to keep them from its water)");
			}
			return positions;
		}
		List<double[]> taken = new ArrayList<>();
		List<ChunkPos> moved = new ArrayList<>(positions.size());
		int count = 0;
		for (ChunkPos pos : positions) {
			double[] spot = map.dryGroundNear(pos.getMiddleBlockX(), pos.getMiddleBlockZ(), taken);
			if (spot == null) {
				moved.add(pos);  // far out, past Skyrim's land
				continue;
			}
			taken.add(spot);
			ChunkPos to = new ChunkPos(Math.floorDiv((int) Math.floor(spot[0]), 16), Math.floorDiv((int) Math.floor(spot[1]), 16));
			if (!to.equals(pos)) {
				count++;
				if (count <= 8) {
					SkyCraft.LOG.info("SkyCraft: stronghold moved under dry land: {} {} -> {} {}", pos.getMiddleBlockX(), pos.getMiddleBlockZ(), to.getMiddleBlockX(),
						to.getMiddleBlockZ());
				}
			}
			moved.add(to);
		}
		SkyCraft.LOG.info("SkyCraft: {} of {} strongholds moved under Skyrim's dry land", count, positions.size());
		return moved;
	}
}
