// SkyCraft+ : living world
package dev.skycraft.mixin;

import dev.skycraft.world.SkyUnderground;
import dev.skycraft.world.SkyWorld;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The mirror world is open sky everywhere, Skyrim's caves and houses included: undead burn in
 * Skyrim's daylight outdoors (the clock follows Skyrim's), but not indoors, and not down in the
 * Minecraft underground under Skyrim's land.
 */
@Mixin(Mob.class)
public abstract class MobSunBurnMixin {
	@Inject(method = "isSunBurnTick", at = @At("HEAD"), cancellable = true)
	private void skycraft$notIndoors(CallbackInfoReturnable<Boolean> cir) {
		if (SkyWorld.valid() && SkyWorld.interior()) {
			cir.setReturnValue(false);
			return;
		}
		Mob self = (Mob) (Object) this;
		if (SkyUnderground.underLand(self.getX(), self.getEyeY(), self.getZ())) {
			cir.setReturnValue(false);
		}
	}
}
