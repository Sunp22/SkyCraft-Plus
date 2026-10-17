// SkyCraft+ : mobs wandering on Skyrim's ground
package dev.skycraft.mixin;

import dev.skycraft.world.SkyCollision;
import dev.skycraft.world.SkyWander;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Before planning a path, Minecraft moves its destination onto the ground: down through air to the
 * first block, or (no block anywhere below) up to the top of the world. Skyrim's ground isn't
 * blocks, so every destination on it went up to the sky (y 1023), and every mob walked to whatever
 * point nearest the sky it could reach: the top of the stairs, the highest terrace, where they
 * piled up. Now the destination goes onto Skyrim's ground near it (or a Minecraft block, as before).
 */
@Mixin(GroundPathNavigation.class)
public abstract class GroundPathNavigationMixin {
	@Inject(method = "findSurfacePosition", at = @At("HEAD"), cancellable = true)
	private void skycraft$skyrimSurface(LevelChunk chunk, BlockPos pos, int reachRange, CallbackInfoReturnable<BlockPos> cir) {
		if (!SkyWander.surfaceFix() || !chunk.getBlockState(pos).isAir()) {
			return;
		}
		// Down to the ground under it (a Minecraft block on the way: as before).
		BlockPos.MutableBlockPos at = pos.mutable();
		for (int i = 0; i <= 16; i++, at.move(0, -1, 0)) {
			if (!chunk.getBlockState(at).isAir()) {
				return;
			}
			if (SkyCollision.blocksPath(at)) {
				break;  // inside Skyrim's ground or a wall: up instead
			}
			if (!Double.isNaN(SkyCollision.floorUnder(at))) {
				cir.setReturnValue(at.immutable());
				return;
			}
		}
		// Up out of Skyrim's ground or a wall, a little way.
		at.set(pos);
		for (int i = 0; i <= 16; i++, at.move(0, 1, 0)) {
			if (!chunk.getBlockState(at).isAir()) {
				return;
			}
			if (!SkyCollision.blocksPath(at) && !Double.isNaN(SkyCollision.floorUnder(at))) {
				cir.setReturnValue(at.immutable());
				return;
			}
		}
		cir.setReturnValue(pos);  // nowhere to stand nearby: left where it was, never sent to the sky
	}
}
