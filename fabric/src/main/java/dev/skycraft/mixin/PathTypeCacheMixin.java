// SkyCraft+ : mob pathfinding
package dev.skycraft.mixin;

import dev.skycraft.world.SkyCollision;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathTypeCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The level's path type cache only forgets a cell when a Minecraft block there changes, but
 * Skyrim's ground arrives (and changes) on its own: a cell looked at before its Skyrim geometry
 * arrived stayed "open air" for good, and mobs took strange routes through walls' worth of nothing.
 * With Skyrim describing the world, each path works its types out afresh (it still caches them
 * for itself while it runs).
 */
@Mixin(PathTypeCache.class)
public abstract class PathTypeCacheMixin {
	@Inject(method = "get", at = @At("HEAD"), cancellable = true)
	private void skycraft$noStaleTypes(int index, long key, CallbackInfoReturnable<PathType> cir) {
		if (SkyCollision.active()) {
			cir.setReturnValue(null);
		}
	}
}
