// SkyCraft+ : interiors kept apart
package dev.skycraft.mixin;

import dev.skycraft.world.SkyInteriors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Which chunks of the interiors dimension hold the current interior's builds (SkyInteriors). */
@Mixin(Level.class)
public abstract class LevelSetBlockMixin {
	@Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("RETURN"))
	private void skycraft$noteInteriorBuild(BlockPos pos, BlockState state, int flags, int limit, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() && !SkyInteriors.swapping()) {
			SkyInteriors.touched((Level) (Object) this, pos);
		}
	}
}
