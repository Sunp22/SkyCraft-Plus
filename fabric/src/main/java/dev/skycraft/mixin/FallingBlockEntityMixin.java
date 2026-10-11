// SkyCraft+ : structure sand
package dev.skycraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.skycraft.world.SkyCollision;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Sand and gravel that fall onto Skyrim ground land there as a block. They came to rest on it but,
 * with only air in the cell below, Minecraft took them to be still falling and broke them.
 */
@Mixin(FallingBlockEntity.class)
public abstract class FallingBlockEntityMixin {
	@WrapOperation(
		method = "tick",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/FallingBlock;isFree(Lnet/minecraft/world/level/block/state/BlockState;)Z")
	)
	private boolean skycraft$landOnSkyrim(BlockState below, Operation<Boolean> original) {
		return original.call(below) && !SkyCollision.supportsFromBelow(((FallingBlockEntity) (Object) this).blockPosition());
	}
}
