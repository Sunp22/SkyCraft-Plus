// SkyCraft+ : hand ownership
package dev.skycraft.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.skycraft.client.SkyHands;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Using items: a hand Skyrim holds is empty to Minecraft (its hotbar item stays there, unseen, and
 * mustn't be placed or eaten). Like a bare hand it still opens doors, chests and crafting tables;
 * with nothing to open, the other hand's item is used. Its mouse button goes to Skyrim.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftUseMixin {
	@WrapOperation(method = "startUseItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getItemInHand(Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/item/ItemStack;"))
	private ItemStack skycraft$skyrimHandIsEmpty(LocalPlayer player, InteractionHand hand, Operation<ItemStack> original) {
		return SkyHands.skyrimOwns(hand) ? ItemStack.EMPTY : original.call(player, hand);
	}
}
