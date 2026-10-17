// SkyCraft+ : mobs wandering on Skyrim's ground
package dev.skycraft.mixin;

import dev.skycraft.world.SkyWander;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A wander goal doesn't set off for a spot it can't reach (SkyWander): it would walk as near as it could, and that's uphill. */
@Mixin(RandomStrollGoal.class)
public abstract class RandomStrollGoalMixin {
	@Shadow
	@Final
	protected PathfinderMob mob;

	@Shadow
	protected double wantedX;

	@Shadow
	protected double wantedY;

	@Shadow
	protected double wantedZ;

	@Inject(method = "canUse", at = @At("RETURN"), cancellable = true)
	private void skycraft$onlyReachable(CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() || !dev.skycraft.world.SkyCollision.active()) {
			return;
		}
		var path = this.mob.getNavigation().createPath(this.wantedX, this.wantedY, this.wantedZ, 0);
		if (SkyWander.skipStroll(this.mob, this.wantedX, this.wantedY, this.wantedZ, path)) {
			cir.setReturnValue(false);
		}
	}
}
