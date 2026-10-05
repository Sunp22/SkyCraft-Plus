// SkyCraft+ : mob pathfinding
package dev.skycraft.mixin;

import dev.skycraft.world.SkyCollision;
import dev.skycraft.world.SkyWater;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mobs stand on Skyrim's ground (BlockCollisionsMixin), but the pathfinder only reads block
 * states: everywhere was air over the void, every path failed, and mobs stood frozen (tamed wolves
 * couldn't follow or teleport either). It now sees Skyrim's ground too: a cell with Skyrim
 * geometry in its upper half is solid, a cell held up by Skyrim ground is walkable, and the floor
 * is where that ground is.
 */
@Mixin(WalkNodeEvaluator.class)
public abstract class WalkNodeEvaluatorMixin {
	@Inject(method = "getPathTypeFromState", at = @At("RETURN"), cancellable = true)
	private static void skycraft$skyrimSolid(BlockGetter level, BlockPos pos, CallbackInfoReturnable<PathType> cir) {
		if (cir.getReturnValue() != PathType.OPEN || !SkyCollision.active()) {
			return;
		}
		if (!SkyCollision.isKnown(pos.getX(), pos.getY(), pos.getZ()) || SkyCollision.blocksPath(pos)) {
			// Skyrim geometry, or somewhere Skyrim hasn't described yet: nothing to walk into.
			cir.setReturnValue(PathType.BLOCKED);
		} else if (SkyWater.depthIn(pos) > 0.0F) {
			// Skyrim's lakes and rivers: mobs avoid water as they do Minecraft's, instead of seeing a
			// dry riverbed a short drop down (off the side of Whiterun's stairs, say).
			cir.setReturnValue(PathType.WATER);
		}
	}

	@Inject(method = "getPathTypeStatic(Lnet/minecraft/world/level/pathfinder/PathfindingContext;Lnet/minecraft/core/BlockPos$MutableBlockPos;)Lnet/minecraft/world/level/pathfinder/PathType;",
		at = @At("RETURN"), cancellable = true)
	private static void skycraft$skyrimGround(PathfindingContext context, BlockPos.MutableBlockPos pos, CallbackInfoReturnable<PathType> cir) {
		if (cir.getReturnValue() == PathType.OPEN && SkyCollision.active() && !Double.isNaN(SkyCollision.floorUnder(pos))) {
			cir.setReturnValue(WalkNodeEvaluator.checkNeighbourBlocks(context, pos.getX(), pos.getY(), pos.getZ(), PathType.WALKABLE));
		}
	}

	@Inject(method = "getFloorLevel(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)D", at = @At("RETURN"), cancellable = true)
	private static void skycraft$skyrimFloor(BlockGetter level, BlockPos pos, CallbackInfoReturnable<Double> cir) {
		if (!SkyCollision.active()) {
			return;
		}
		double floor = SkyCollision.floorUnder(pos);
		if (!Double.isNaN(floor) && floor > cir.getReturnValue()) {
			cir.setReturnValue(floor);
		}
	}
}
