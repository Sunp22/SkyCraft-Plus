// SkyCraft+ : Skyrim poisons on Minecraft weapons
package dev.skycraft.net;

import java.util.List;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

/**
 * Skyrim poisons on Minecraft weapons. A Skyrim poison used while Minecraft holds the right hand
 * (a sword, an axe, a trident...) coats that weapon instead (Skyrim uses the poison up), and its
 * next hit deals the poison's damage on top: as magic, so Minecraft's undead (most of its
 * monsters, whom its own poison can't touch) feel it too.
 */
public final class SkyPoison {
	private SkyPoison() {
	}

	private static final String TAG = "skycraft_poison";

	public static void init() {
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!level.isClientSide() && player instanceof ServerPlayer server && entity instanceof LivingEntity target) {
				ItemStack weapon = player.getMainHandItem();
				float damage = damageOn(weapon);
				if (damage > 0.0F) {
					clear(weapon);
					// After the weapon's own hit (which makes the target briefly unhurtable).
					server.level().getServer().execute(() -> {
						if (target.isAlive()) {
							target.setInvulnerableTime(0);
							target.hurtServer((ServerLevel) target.level(), target.damageSources().indirectMagic(server, server), damage);
							target.level().playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.BREWING_STAND_BREW, SoundSource.PLAYERS, 0.5F, 1.6F);
						}
					});
				}
			}
			return InteractionResult.PASS;
		});
	}

	/** Can this hold a poison: a Minecraft weapon (not a Skyrim shortcut). */
	public static boolean poisonable(ItemStack stack) {
		return !stack.isEmpty() && stack.has(DataComponents.WEAPON) && !SkyShortcuts.isShortcut(stack);
	}

	/** Minecraft damage for a Skyrim poison: its whole harm (magnitude, over its duration) a third of it, 2 to 24. */
	public static float minecraftDamage(float magnitude, float seconds) {
		return Math.clamp(magnitude * Math.max(1.0F, seconds) / 3.0F, 2.0F, 24.0F);
	}

	/** Server: the main-hand weapon takes the poison (its next hit). */
	static void coat(ServerPlayer player, float damage) {
		ItemStack weapon = player.getMainHandItem();
		if (!poisonable(weapon) || damage <= 0.0F) {
			return;
		}
		CustomData.update(DataComponents.CUSTOM_DATA, weapon, tag -> tag.putFloat(TAG, damage));
		weapon.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Poisoned: " + Math.round(damage) + " Skyrim poison damage").withStyle(ChatFormatting.DARK_GREEN))));
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BOTTLE_EMPTY, SoundSource.PLAYERS, 1.0F, 1.0F);
	}

	static float damageOn(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) {
			return 0.0F;
		}
		CompoundTag tag = data.copyTag();
		return tag.getFloatOr(TAG, 0.0F);
	}

	static void clear(ItemStack stack) {
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.remove(TAG));
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore != null && !lore.lines().isEmpty() && lore.lines().getFirst().getString().startsWith("Poisoned: ")) {
			stack.remove(DataComponents.LORE);
		}
	}
}
