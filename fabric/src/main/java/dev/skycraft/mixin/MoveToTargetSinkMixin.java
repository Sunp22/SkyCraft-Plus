// SkyCraft+ : mobs wandering on Skyrim's ground
package dev.skycraft.mixin;

import dev.skycraft.world.SkyWander;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.MoveToTargetSink;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.level.pathfinder.Path;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Villagers, goats and other brain-driven mobs: where they head and whether they can get there (logged, SkyWander). */
@Mixin(MoveToTargetSink.class)
public abstract class MoveToTargetSinkMixin {
	@Shadow
	private @Nullable Path path;

	@Inject(method = "tryComputePath", at = @At("RETURN"))
	private void skycraft$logWalk(Mob body, WalkTarget walkTarget, long timestamp, CallbackInfoReturnable<Boolean> cir) {
		if (dev.skycraft.world.SkyCollision.active()) {
			SkyWander.brainPath(body, walkTarget.getTarget().currentBlockPosition(), this.path);
		}
	}
}
