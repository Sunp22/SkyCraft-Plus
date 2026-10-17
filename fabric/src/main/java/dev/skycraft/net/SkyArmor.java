// SkyCraft+ : Skyrim armor in Minecraft's armor slots
package dev.skycraft.net;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.equipment.Equippable;
import org.jspecify.annotations.Nullable;

/**
 * Skyrim armor in Minecraft's armor slots. A Skyrim helmet (hood, circlet), cuirass (robes) or boots
 * goes in Minecraft's head, chest or feet slot as its shortcut, made Minecraft armor: armor points
 * (and toughness for heavy armor) in proportion to Skyrim's armor rating, shown on the HUD. It is
 * also really worn in Skyrim, so its enchantments work there. Gauntlets have no Minecraft slot: their
 * armor is added to the player's (HandsArmor), and they show in a slot of the Skyrim panels' own.
 *
 * Skyrim's hits already went through Skyrim's own armor and resistances before they reach
 * Minecraft, so against those this armor doesn't count again (skyrimHit, LivingEntityArmorMixin);
 * against Minecraft's mobs it's the only armor that counts.
 */
public final class SkyArmor {
	private SkyArmor() {
	}

	private static final Identifier HANDS_ID = Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "skyrim_gauntlets");
	private static final Identifier PIECE_ID = Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "skyrim_armor");

	/** Set while a Skyrim hit is being dealt to a player (SkyCombat.hurtPlayer). */
	public static boolean skyrimHit;

	/** The Minecraft slot a Skyrim apparel slot (Proto.A_*) goes in, or null (no Minecraft slot for it). */
	public static @Nullable EquipmentSlot slotFor(int sub) {
		return switch (sub) {
			case Proto.A_HEAD, Proto.A_CIRCLET -> EquipmentSlot.HEAD;
			case Proto.A_BODY -> EquipmentSlot.CHEST;
			case Proto.A_FEET -> EquipmentSlot.FEET;
			default -> null;
		};
	}

	/** Minecraft's inventory index for an armor slot (36 feet .. 39 head). */
	public static int inventoryIndex(EquipmentSlot slot) {
		return 36 + slot.getIndex();
	}

	/**
	 * Minecraft armor points for a Skyrim armor rating in that slot: about a diamond piece's for the
	 * best Skyrim armor (Daedric 23 / 49 / 18 / 18 against diamond's 3 / 8 / 3 and gauntlets 3), so
	 * a full set of good Skyrim armor (it has no leggings) is about full diamond.
	 */
	public static double points(int sub, float rating) {
		double p = switch (sub) {
			case Proto.A_HEAD, Proto.A_CIRCLET -> rating / 23.0 * 3.5;
			case Proto.A_BODY -> rating / 49.0 * 9.0;
			case Proto.A_FEET -> rating / 18.0 * 3.5;
			case Proto.A_HANDS -> rating / 18.0 * 3.0;
			default -> 0.0;
		};
		return Math.round(Math.min(p, 12.0) * 2.0) / 2.0;
	}

	public static double toughness(int sub, float rating, int flags) {
		return (flags & Proto.ITEM_HEAVY) != 0 ? Math.round(points(sub, rating) * 0.3 * 2.0) / 2.0 : 0.0;
	}

	/** Makes a Skyrim apparel shortcut Minecraft armor for its slot (if it has one). */
	static void makeArmor(ItemStack stack, int kind, int sub, float rating, int flags) {
		EquipmentSlot slot = kind == Proto.SKY_APPAREL ? slotFor(sub) : null;
		if (slot == null) {
			return;
		}
		boolean heavy = (flags & Proto.ITEM_HEAVY) != 0;
		stack.set(DataComponents.EQUIPPABLE, Equippable.builder(slot).setEquipSound(heavy ? SoundEvents.ARMOR_EQUIP_IRON : SoundEvents.ARMOR_EQUIP_LEATHER).build());
		ItemAttributeModifiers.Builder modifiers = ItemAttributeModifiers.builder();
		EquipmentSlotGroup group = EquipmentSlotGroup.bySlot(slot);
		modifiers.add(Attributes.ARMOR, new AttributeModifier(PIECE_ID.withSuffix("." + slot.getName()), points(sub, rating), AttributeModifier.Operation.ADD_VALUE), group);
		double tough = toughness(sub, rating, flags);
		if (tough > 0.0) {
			modifiers.add(Attributes.ARMOR_TOUGHNESS, new AttributeModifier(PIECE_ID.withSuffix("." + slot.getName()), tough, AttributeModifier.Operation.ADD_VALUE), group);
		}
		stack.set(DataComponents.ATTRIBUTE_MODIFIERS, modifiers.build());
	}

	/** The armor and toughness Skyrim armor gives this entity (its shortcuts in armor slots and its gauntlets). */
	public static double[] skyrimArmorOf(LivingEntity entity) {
		double armor = 0.0, tough = 0.0;
		for (EquipmentSlot slot : new EquipmentSlot[] { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET }) {
			ItemStack stack = entity.getItemBySlot(slot);
			if (!SkyShortcuts.isShortcut(stack)) {
				continue;
			}
			ItemAttributeModifiers modifiers = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
			for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
				if (entry.attribute().is(Attributes.ARMOR)) {
					armor += entry.modifier().amount();
				} else if (entry.attribute().is(Attributes.ARMOR_TOUGHNESS)) {
					tough += entry.modifier().amount();
				}
			}
		}
		AttributeInstance a = entity.getAttribute(Attributes.ARMOR);
		AttributeModifier hands = a == null ? null : a.getModifier(HANDS_ID);
		armor += hands == null ? 0.0 : hands.amount();
		AttributeInstance t = entity.getAttribute(Attributes.ARMOR_TOUGHNESS);
		AttributeModifier handsTough = t == null ? null : t.getModifier(HANDS_ID);
		tough += handsTough == null ? 0.0 : handsTough.amount();
		return new double[] { armor, tough };
	}

	/** Client -> server: a Skyrim piece put on: its shortcut, made armor, into its armor slot (a Minecraft piece there goes into the inventory). */
	public record Wear(SkyShortcuts.Make make) implements CustomPacketPayload {
		public static final Type<Wear> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "armor_wear"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Wear> CODEC = StreamCodec.of((buf, w) -> SkyShortcuts.Make.CODEC.encode(buf, w.make),
			buf -> new Wear(SkyShortcuts.Make.CODEC.decode(buf)));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client -> server: Skyrim took that piece off: its shortcut leaves the armor slot. */
	public record Clear(int slot) implements CustomPacketPayload {
		public static final Type<Clear> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "armor_clear"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Clear> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Clear::slot, Clear::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client -> server: the armor (and toughness) of the Skyrim gauntlets worn (0: none). */
	public record HandsArmor(float armor, float toughness) implements CustomPacketPayload {
		public static final Type<HandsArmor> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "armor_hands"));
		public static final StreamCodec<RegistryFriendlyByteBuf, HandsArmor> CODEC = StreamCodec.composite(ByteBufCodecs.FLOAT, HandsArmor::armor, ByteBufCodecs.FLOAT,
			HandsArmor::toughness, HandsArmor::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	private static void handsArmor(Player player, double armor, double toughness) {
		AttributeInstance a = player.getAttribute(Attributes.ARMOR);
		AttributeInstance t = player.getAttribute(Attributes.ARMOR_TOUGHNESS);
		if (a != null) {
			a.removeModifier(HANDS_ID);
			if (armor > 0.0) {
				a.addTransientModifier(new AttributeModifier(HANDS_ID, armor, AttributeModifier.Operation.ADD_VALUE));
			}
		}
		if (t != null) {
			t.removeModifier(HANDS_ID);
			if (toughness > 0.0) {
				t.addTransientModifier(new AttributeModifier(HANDS_ID, toughness, AttributeModifier.Operation.ADD_VALUE));
			}
		}
	}

	public static void init() {
		PayloadTypeRegistry.serverboundPlay().register(Wear.TYPE, Wear.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Clear.TYPE, Clear.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(HandsArmor.TYPE, HandsArmor.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(Wear.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> {
				SkyShortcuts.Make make = payload.make();
				EquipmentSlot slot = slotFor(make.sub());
				ItemStack stack = make.kind() == Proto.SKY_APPAREL && slot != null ? SkyShortcuts.make(make) : null;
				if (stack == null) {
					return;
				}
				var inventory = player.getInventory();
				int index = inventoryIndex(slot);
				ItemStack there = inventory.getItem(index);
				if (!there.isEmpty() && !SkyShortcuts.isShortcut(there)) {
					inventory.setItem(index, ItemStack.EMPTY);
					inventory.placeItemBackInInventory(there, net.minecraft.util.Prediction.SERVER_ONLY);  // (or dropped, if full)
				}
				inventory.setItem(index, stack);
				player.containerMenu.broadcastChanges();
			});
		});
		ServerPlayNetworking.registerGlobalReceiver(Clear.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> {
				int index = payload.slot();
				if (index >= 36 && index <= 39 && SkyShortcuts.isShortcut(player.getInventory().getItem(index))) {
					player.getInventory().setItem(index, ItemStack.EMPTY);
					player.containerMenu.broadcastChanges();
				}
			});
		});
		ServerPlayNetworking.registerGlobalReceiver(HandsArmor.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			double armor = Math.clamp(payload.armor(), 0.0F, 6.0F), tough = Math.clamp(payload.toughness(), 0.0F, 3.0F);
			context.server().execute(() -> handsArmor(player, armor, tough));
		});
	}
}
