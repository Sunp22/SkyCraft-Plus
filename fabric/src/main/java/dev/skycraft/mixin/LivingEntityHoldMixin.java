// SkyCraft+ : mobs held where Skyrim's ground is unknown
package dev.skycraft.mixin;

import dev.skycraft.world.SkyHold;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A mob over Skyrim's ground that isn't known yet doesn't move or fall (SkyHold). */
@Mixin(LivingEntity.class)
public abstract class LivingEntityHoldMixin {
	@Inject(method = "travel", at = @At("HEAD"), cancellable = true)
	private void skycraft$holdWithoutGround(Vec3 input, CallbackInfo ci) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (SkyHold.hold(self)) {
			self.setDeltaMovement(Vec3.ZERO);
			self.resetFallDistance();
			ci.cancel();
		}
	}
}
