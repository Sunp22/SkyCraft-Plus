// SkyCraft+ : Skyrim menus pause Minecraft
package dev.skycraft.client.mixin;

import dev.skycraft.client.SkyClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While a Skyrim menu pauses Skyrim (inventory, magic, map...), Minecraft pauses too, as its own
 * pause menu would in singleplayer: its mobs don't attack the player meanwhile. Not while the world
 * is open to LAN (vanilla doesn't pause then either).
 */
@Mixin(Minecraft.class)
public abstract class MinecraftPauseMixin {
	@Inject(method = "isPaused", at = @At("RETURN"), cancellable = true)
	private void skycraft$skyrimPaused(CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() || !SkyClient.linked() || !SkyClient.sky().skyrimPaused()) {
			return;
		}
		var server = ((Minecraft) (Object) this).getSingleplayerServer();
		if (server != null && !server.isPublished()) {
			cir.setReturnValue(true);
		}
	}
}
