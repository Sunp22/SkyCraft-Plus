// SkyCraft+ : hand ownership
package dev.skycraft.client.mixin;

import dev.skycraft.client.SkyHands;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The player's body in third person (drawn in Skyrim) holds a Minecraft stand-in for a Skyrim
 * weapon, shield or torch in a Skyrim-held hand, rather than nothing (SkyHands.standIn).
 */
@Mixin(ArmedEntityRenderState.class)
public abstract class ArmedEntityStandInMixin {
	@Inject(method = "extractArmedEntityRenderState", at = @At("TAIL"), require = 0)  // (cosmetic: never worth a crash)
	private static void skycraft$skyrimHandStandIn(LivingEntity entity, ArmedEntityRenderState state, ItemModelResolver itemModelResolver, float partialTicks,
		CallbackInfo ci) {
		if (entity != Minecraft.getInstance().player) {
			return;
		}
		for (HumanoidArm arm : HumanoidArm.values()) {
			var stack = SkyHands.standIn(arm);
			if (stack.isEmpty()) {
				continue;
			}
			boolean right = arm == HumanoidArm.RIGHT;
			itemModelResolver.updateForLiving(right ? state.rightHandItemState : state.leftHandItemState, stack,
				right ? ItemDisplayContext.THIRD_PERSON_RIGHT_HAND : ItemDisplayContext.THIRD_PERSON_LEFT_HAND, entity);
			if (right) {
				state.rightHandItemStack = stack;
			} else {
				state.leftHandItemStack = stack;
			}
		}
	}
}
