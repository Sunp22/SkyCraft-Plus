// SkyCraft+ : Minecraft's structures in Skyrim's world
package dev.skycraft.mixin;

import dev.skycraft.world.SkyStructures;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While SkyStructures places an ocean structure, the sea is Skyrim's water there: a monument fills
 * its inside with water up to the sea level and clears it above, and with Minecraft's (y 63, far
 * above Skyrim's sea) it stood in a tower of water hanging over Skyrim's.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelSeaMixin {
	@Inject(method = "getSeaLevel", at = @At("HEAD"), cancellable = true)
	private void skycraft$skyrimSea(CallbackInfoReturnable<Integer> cir) {
		int sea = SkyStructures.placingSea;
		if (sea != Integer.MIN_VALUE && SkyStructures.placingIn(this)) {
			cir.setReturnValue(sea);
		}
	}
}
