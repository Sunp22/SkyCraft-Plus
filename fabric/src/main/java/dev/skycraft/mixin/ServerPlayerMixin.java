package dev.skycraft.mixin;

import dev.skycraft.SkyCraft;
import dev.skycraft.combat.SkyCombat;
import dev.skycraft.combat.SkyrimActorEntity;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.server.level.ServerLevel;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
	/** Critical hits on a Skyrim actor are flagged so Skyrim can play them up. */
	@Inject(method = "crit", at = @At("HEAD"))
	private void skycraft$critSkyrim(Entity entity, CallbackInfo ci) {
		if (entity instanceof SkyrimActorEntity proxy) {
			proxy.markCritical();
		}
	}

	/**
	 * SkyCraft+ : Skyrim combat on Minecraft mobs. Hurt by a Minecraft mob, its arrow or an
	 * explosion (not a Skyrim NPC: Skyrim handles those):
	 * - in Skyrim mode the hit goes to the Skyrim player instead (its share of Minecraft's health,
	 *   as Skyrim's share of its own), since the Minecraft player only follows along;
	 * - blocking with Skyrim gear stops it from the front, as a Minecraft shield would.
	 */
	@Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
	private void skycraft$skyrimDefence(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
		ServerPlayer self = (ServerPlayer) (Object) this;
		Entity attacker = source.getEntity();
		boolean fromMinecraft = (attacker != null && !(attacker instanceof SkyrimActorEntity) && attacker != self)
			|| source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION);
		if (!fromMinecraft || !dev.skycraft.net.SkyNet.isHost(self) || !SkyLink.active()) {
			return;
		}
		if (SkyCombat.skyrimBlocking && !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_SHIELD) && source.getSourcePosition() != null) {
			var to = source.getSourcePosition().subtract(self.position());
			var look = self.getLookAngle();
			if (to.x * look.x + to.z * look.z > 0.0) {
				level.playSound(null, self.getX(), self.getY(), self.getZ(), net.minecraft.sounds.SoundEvents.SHIELD_BLOCK.value(),
					net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 0.8F + level.getRandom().nextFloat() * 0.4F);
				cir.setReturnValue(false);
				return;
			}
		}
		if (SkyCombat.skyrimDrives && amount > 0.0F) {
			SkyLink.pushEvent(Proto.EV_PLAYER_HURT, 0, amount / self.getMaxHealth(), 0, 0, 0, 0);
			cir.setReturnValue(false);
		}
	}

	/** While Skyrim drives the host's player, the Minecraft one following it can't be hurt. */
	@Inject(method = "isInvulnerableTo", at = @At("HEAD"), cancellable = true)
	private void skycraft$followingSkyrim(ServerLevel level, DamageSource source, CallbackInfoReturnable<Boolean> cir) {
		if (SkyCombat.skyrimDrives && dev.skycraft.net.SkyNet.isHost((ServerPlayer) (Object) this)) {
			cir.setReturnValue(true);
		}
	}

	/** Dying in Minecraft is dying in Skyrim: the host's through the link, a guest's through theirs. */
	@Inject(method = "die", at = @At("HEAD"))
	private void skycraft$diesInSkyrim(DamageSource source, CallbackInfo ci) {
		ServerPlayer self = (ServerPlayer) (Object) this;
		int attacker = SkyCombat.attackerFormId(source);
		if (!dev.skycraft.net.SkyNet.isHost(self)) {
			if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(self, dev.skycraft.net.SkyNet.Died.TYPE)) {
				net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(self, new dev.skycraft.net.SkyNet.Died(attacker));
			}
			SkyCraft.LOG.info("SkyCraft: guest {} died ({}); telling their Skyrim", self.getPlainTextName(), source.getMsgId());
			return;
		}
		if (SkyLink.active()) {
			SkyLink.pushEvent(Proto.EV_PLAYER_DIED, attacker, 0, 0, 0, 0, 0);
			SkyCraft.LOG.info("SkyCraft: player died ({}); telling Skyrim", source.getMsgId());
		}
	}
}
