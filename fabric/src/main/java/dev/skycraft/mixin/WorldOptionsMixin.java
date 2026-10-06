// SkyCraft+ : the Minecraft underground under Skyrim
package dev.skycraft.mixin;

import dev.skycraft.world.SkyUnderground;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The mirror world is made with structures off (its flat void has none anyway), which also took
 * them from the underground's hidden world, the Nether and the End: no strongholds, mineshafts,
 * fortresses or End cities, and nothing for eyes of ender to find. With the underground on, worlds
 * made that way get them (in ground generated from now on).
 */
@Mixin(WorldOptions.class)
public abstract class WorldOptionsMixin {
	@Inject(method = "generateStructures", at = @At("HEAD"), cancellable = true)
	private void skycraft$structures(CallbackInfoReturnable<Boolean> cir) {
		if (SkyUnderground.enabled()) {
			cir.setReturnValue(true);
		}
	}
}
