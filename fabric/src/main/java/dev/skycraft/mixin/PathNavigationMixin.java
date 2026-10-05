// SkyCraft+ : mob pathfinding
package dev.skycraft.mixin;

import dev.skycraft.world.SkyCollision;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Skyrim ground is somewhere to go: mobs wandering about pick spots standing on it. */
@Mixin(PathNavigation.class)
public abstract class PathNavigationMixin {
	@Inject(method = "isStableDestination", at = @At("RETURN"), cancellable = true)
	private void skycraft$skyrimGround(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() && SkyCollision.active() && !Double.isNaN(SkyCollision.floorUnder(pos))) {
			cir.setReturnValue(true);
		}
	}
}
