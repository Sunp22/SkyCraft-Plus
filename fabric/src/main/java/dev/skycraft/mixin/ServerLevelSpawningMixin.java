// SkyCraft+ : the Nether and the End
package dev.skycraft.mixin;

import dev.skycraft.world.SkyDig;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * SkyCraft turns Minecraft's mob spawning off (its own spawner fills Skyrim's worlds). Minecraft's
 * own worlds, the Nether and the End, spawn as vanilla: ghasts, piglins, endermen and the rest.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelSpawningMixin {
	@Inject(method = "isSpawningMonsters", at = @At("HEAD"), cancellable = true)
	private void skycraft$vanillaInMinecraftsWorlds(CallbackInfoReturnable<Boolean> cir) {
		if (SkyDig.minecraftsOwnWorld((ServerLevel) (Object) this)) {
			cir.setReturnValue(true);
		}
	}
}
