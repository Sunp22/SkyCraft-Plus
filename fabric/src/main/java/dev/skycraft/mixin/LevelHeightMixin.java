// SkyCraft+ : Minecraft's structures in Skyrim's world
package dev.skycraft.mixin;

import dev.skycraft.world.SkyStructures;
import dev.skycraft.world.SkyrimMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While SkyStructures places one of Minecraft's structures in the mirror world (which has no
 * terrain), the ground it looks for is Skyrim's land there: the heights its pieces follow (paths
 * along the land, a temple's floor, a wreck on the sea floor), and solid stone under it, where its
 * foundations stop (a mansion's or a hut's legs filled down to the world's floor otherwise).
 */
@Mixin(Level.class)
public abstract class LevelHeightMixin {
	@Inject(method = "getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I", at = @At("RETURN"), cancellable = true)
	private void skycraft$skyrimGround(Heightmap.Types type, int x, int z, CallbackInfoReturnable<Integer> cir) {
		SkyrimMap map = SkyStructures.placing;
		if (map != null && SkyStructures.placingIn(this)) {
			cir.setReturnValue(SkyStructures.heightWhilePlacing(map, type, x, z, cir.getReturnValue()));
		}
	}

	@Inject(method = "getBlockState", at = @At("RETURN"), cancellable = true)
	private void skycraft$skyrimGroundIsSolid(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
		SkyrimMap map = SkyStructures.placing;
		// (Not the structure's own rooms: a chest is turned away from its one solid neighbour, and in
		// a temple's chest room every side looked solid, so all four faced the same way.)
		if (map != null && cir.getReturnValue().isAir() && SkyStructures.placingIn(this) && !SkyStructures.wrote(pos)) {
			double ground = map.groundAt(pos.getX() + 0.5, pos.getZ() + 0.5);
			if (!Double.isNaN(ground) && pos.getY() < Math.round(ground)) {
				cir.setReturnValue(Blocks.STONE.defaultBlockState());
			}
		}
	}
}
