// SkyCraft+ : Skyrim armor in Minecraft's armor slots
package dev.skycraft.mixin;

import dev.skycraft.net.SkyArmor;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.tags.DamageTypeTags;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A Skyrim hit already went through Skyrim's own armor (and resistances) before Minecraft got it:
 * the Skyrim armor in Minecraft's slots doesn't count against it again. Minecraft's own armor does.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityArmorMixin {
	@Shadow
	protected abstract void hurtArmor(DamageSource damageSource, float damage);

	@Inject(method = "getDamageAfterArmorAbsorb", at = @At("HEAD"), cancellable = true)
	private void skycraft$notTwice(DamageSource source, float damage, CallbackInfoReturnable<Float> cir) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (!SkyArmor.skyrimHit || !(self instanceof Player) || source.is(DamageTypeTags.BYPASSES_ARMOR)) {
			return;
		}
		double[] skyrim = SkyArmor.skyrimArmorOf(self);
		if (skyrim[0] <= 0.0 && skyrim[1] <= 0.0) {
			return;
		}
		this.hurtArmor(source, damage);
		float armor = (float) Math.max(0.0, self.getArmorValue() - skyrim[0]);
		float toughness = (float) Math.max(0.0, self.getAttributeValue(Attributes.ARMOR_TOUGHNESS) - skyrim[1]);
		cir.setReturnValue(CombatRules.getDamageAfterAbsorb(self, damage, source, armor, toughness));
	}
}
