// SkyCraft+ : hand ownership
package dev.skycraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.skycraft.combat.SkyCombat;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The server's half of using with a hand Skyrim holds: it's empty, so a door or chest opens by hand
 * and the hidden hotbar item is never placed or eaten (the host only: it's the host's Skyrim).
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {
	@WrapOperation(method = { "handleUseItemOn", "handleUseItem" }, at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;getItemInHand(Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/item/ItemStack;"))
	private ItemStack skycraft$skyrimHandIsEmpty(ServerPlayer player, InteractionHand hand, Operation<ItemStack> original) {
		return SkyCombat.skyrimHolds(player, hand) ? ItemStack.EMPTY : original.call(player, hand);
	}
}
