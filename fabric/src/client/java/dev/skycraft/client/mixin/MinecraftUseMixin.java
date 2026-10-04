// SkyCraft+ : hand ownership
package dev.skycraft.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.skycraft.client.SkyHands;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Using items tries each hand in turn; a hand Skyrim holds is skipped (its hotbar item stays in
 * Minecraft's hand, unseen, and mustn't be placed or eaten). Its mouse button goes to Skyrim.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftUseMixin {
	@WrapOperation(method = "startUseItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/InteractionHand;values()[Lnet/minecraft/world/InteractionHand;"))
	private InteractionHand[] skycraft$minecraftHandsOnly(Operation<InteractionHand[]> original) {
		InteractionHand[] hands = original.call();
		return Arrays.stream(hands).filter(hand -> !SkyHands.skyrimOwns(hand)).toArray(InteractionHand[]::new);
	}
}
