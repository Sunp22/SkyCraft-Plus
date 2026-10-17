// SkyCraft+ : mobs wandering on Skyrim's ground
package dev.skycraft.mixin;

import dev.skycraft.world.SkyWander;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.util.GoalUtils;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.entity.ai.util.RandomPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A wander spot inside a Skyrim wall or cliff isn't moved up onto its top (SkyWander): it's thrown away. */
@Mixin(LandRandomPos.class)
public abstract class LandRandomPosMixin {
	@Inject(method = "movePosUpOutOfSolid", at = @At("HEAD"), cancellable = true)
	private static void skycraft$notOntoWalls(PathfinderMob mob, BlockPos pos, CallbackInfoReturnable<BlockPos> cir) {
		if (!dev.skycraft.world.SkyCollision.active()) {
			return;
		}
		BlockPos moved = RandomPos.moveUpOutOfSolid(pos, mob.level().getMaxY(), p -> GoalUtils.isSolid(mob, p));
		if (SkyWander.tooHigh(mob, pos, moved)) {
			cir.setReturnValue(null);
		}
	}
}
