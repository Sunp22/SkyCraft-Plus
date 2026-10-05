// SkyCraft+ : living world
package dev.skycraft.mixin;

import dev.skycraft.world.SkyWorld;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The mirror world is open sky everywhere, Skyrim's caves and houses included: undead burn in
 * Skyrim's daylight outdoors (the clock follows Skyrim's), but not indoors.
 */
@Mixin(Mob.class)
public abstract class MobSunBurnMixin {
	@Inject(method = "isSunBurnTick", at = @At("HEAD"), cancellable = true)
	private void skycraft$notIndoors(CallbackInfoReturnable<Boolean> cir) {
		if (SkyWorld.valid() && SkyWorld.interior()) {
			cir.setReturnValue(false);
		}
	}
}
