// SkyCraft+ : mob pathfinding
package dev.skycraft.mixin;

import dev.skycraft.world.SkyCollision;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.util.GoalUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A random spot inside Skyrim's ground or a wall is solid, so it's moved up out of it. */
@Mixin(GoalUtils.class)
public abstract class GoalUtilsMixin {
	@Inject(method = "isSolid", at = @At("RETURN"), cancellable = true)
	private static void skycraft$skyrimSolid(PathfinderMob mob, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() && SkyCollision.active() && SkyCollision.blocksPath(pos)) {
			cir.setReturnValue(true);
		}
	}
}
