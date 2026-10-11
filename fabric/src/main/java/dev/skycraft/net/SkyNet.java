package dev.skycraft.net;

import dev.skycraft.SkyCraft;
import dev.skycraft.combat.SkyCombat;
import dev.skycraft.world.SkyDig;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Multiplayer: every player has their own Skyrim, talking to their own Minecraft client. The host's
 * Skyrim reaches the host's integrated server through shared memory; a guest's Skyrim reaches the
 * host's server through these packets instead.
 */
public final class SkyNet {
	private SkyNet() {
	}

	/** Guest -> server: the guest's Skyrim hit them (as proto::InputEvent kInHurt). */
	public record Hurt(int kind, float skyrimDamage, int attackerFormId, int flags) implements CustomPacketPayload {
		public static final Type<Hurt> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "hurt"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Hurt> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, Hurt::kind,
			ByteBufCodecs.FLOAT, Hurt::skyrimDamage,
			ByteBufCodecs.INT, Hurt::attackerFormId,
			ByteBufCodecs.VAR_INT, Hurt::flags,
			Hurt::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Server -> guest: the guest died in Minecraft, so their Skyrim player dies too. */
	public record Died(int attackerFormId) implements CustomPacketPayload {
		public static final Type<Died> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "died"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Died> CODEC = StreamCodec.composite(ByteBufCodecs.INT, Died::attackerFormId, Died::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client -> server: the player hit Skyrim's geometry in this cell (SkyDig.open). */
	public record DigOpen(int world, BlockPos pos, int material) implements CustomPacketPayload {
		public static final Type<DigOpen> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "dig_open"));
		public static final StreamCodec<RegistryFriendlyByteBuf, DigOpen> CODEC = StreamCodec.composite(
			ByteBufCodecs.INT, DigOpen::world,
			BlockPos.STREAM_CODEC, DigOpen::pos,
			ByteBufCodecs.VAR_INT, DigOpen::material,
			DigOpen::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client -> server: cells around a broken dug block that are inside Skyrim's geometry (SkyDig.reveal). */
	public record DigReveal(int world, List<BlockPos> cells, List<Integer> materials) implements CustomPacketPayload {
		public static final Type<DigReveal> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "dig_reveal"));
		public static final StreamCodec<RegistryFriendlyByteBuf, DigReveal> CODEC = StreamCodec.composite(
			ByteBufCodecs.INT, DigReveal::world,
			BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list(64)), DigReveal::cells,
			ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(64)), DigReveal::materials,
			DigReveal::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * SkyCraft+ : multiplayer. Something a player's Skyrim did that Minecraft's server carries out:
	 * use a block by hand (Skyrim mode's E), heal (a Skyrim spell or potion), take over Skyrim's share
	 * of health, a Skyrim melee hit, which hands Skyrim holds. The host's client runs it on its own
	 * server; a guest's sends it to the host's.
	 */
	public record Act(int kind, int code, float a, float b, BlockPos pos, int face, float hx, float hy, float hz) implements CustomPacketPayload {
		public static final int USE_BLOCK = 0, HEAL = 1, SKYRIM_HIT = 2, SET_HEALTH = 3, HANDS = 4, BLOCK_HIT = 5;
		public static final Type<Act> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "act"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Act> CODEC = StreamCodec.of((buf, act) -> {
			buf.writeVarInt(act.kind);
			buf.writeVarInt(act.code);
			buf.writeFloat(act.a);
			buf.writeFloat(act.b);
			buf.writeBlockPos(act.pos);
			buf.writeVarInt(act.face);
			buf.writeFloat(act.hx);
			buf.writeFloat(act.hy);
			buf.writeFloat(act.hz);
		}, buf -> new Act(buf.readVarInt(), buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readBlockPos(), buf.readVarInt(), buf.readFloat(), buf.readFloat(),
			buf.readFloat()));

		public static Act simple(int kind, int code, float a, float b) {
			return new Act(kind, code, a, b, BlockPos.ZERO, 0, 0.0F, 0.0F, 0.0F);
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** SkyCraft+ : multiplayer. Which hands each guest's Skyrim holds (bit 1: right, 2: left). */
	private static final java.util.Map<java.util.UUID, Integer> GUEST_HANDS = new java.util.concurrent.ConcurrentHashMap<>();

	public static int guestHands(ServerPlayer player) {
		return GUEST_HANDS.getOrDefault(player.getUUID(), 0) & 3;
	}

	/** SkyCraft+ : Skyrim weapons break blocks. As guestHands, with what they hold (Proto.HAND_*) in bits 8-15 (right) and 16-23 (left). */
	public static int guestHandsAndKinds(ServerPlayer player) {
		return GUEST_HANDS.getOrDefault(player.getUUID(), 0);
	}

	/** Carries out a player's Skyrim action on the server thread (amounts kept sane: a guest's client sent them). */
	public static void apply(ServerPlayer player, Act act) {
		switch (act.kind) {
			case Act.USE_BLOCK -> {
				var hit = new net.minecraft.world.phys.BlockHitResult(new net.minecraft.world.phys.Vec3(act.hx, act.hy, act.hz),
					net.minecraft.core.Direction.from3DDataValue(act.face), act.pos, false);
				if (player.getEyePosition().distanceToSqr(hit.getLocation()) > 8.0 * 8.0
					|| player.getEyePosition().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(act.pos)) > 8.0 * 8.0
					|| !player.level().mayInteract(player, act.pos)) {
					return;  // (the block itself in reach, and not spawn-protected)
				}
				var state = player.level().getBlockState(act.pos);
				var result = state.useWithoutItem(player.level(), player, hit);
				SkyCraft.LOG.info("SkyCraft: Skyrim mode use: {} at {}: {}", state.getBlock().getName().getString(), act.pos.toShortString(), result);
			}
			case Act.HEAL -> {
				if (player.isAlive()) {
					player.heal(Math.max(0.0F, Math.min(act.a, 1.0F)) * player.getMaxHealth());
				}
			}
			case Act.SET_HEALTH -> {
				if (!player.isDeadOrDying()) {
					float before = player.getHealth();
					player.setHealth(Math.max(1.0F, Math.max(0.0F, Math.min(act.a, 1.0F)) * player.getMaxHealth()));
					SkyCraft.LOG.info("SkyCraft: health {} -> {} (Skyrim's {}%)", before, player.getHealth(), Math.round(act.a * 100.0F));
				}
			}
			case Act.SKYRIM_HIT -> SkyCombat.skyrimMelee(player, act.code, Math.max(0.0F, Math.min(act.a, 1000.0F)), Math.max(0.0F, Math.min(act.b, 8.0F)));
			case Act.HANDS -> GUEST_HANDS.put(player.getUUID(), act.code & 0xFFFF03);
			// SkyCraft+ : Skyrim weapons break blocks. The swing landed on this Minecraft block.
			case Act.BLOCK_HIT -> dev.skycraft.combat.SkyWeaponTools.hit(player, act.pos, (act.code & dev.skycraft.link.Proto.SKYRIM_HIT_LEFT) != 0, (act.code & dev.skycraft.link.Proto.SKYRIM_HIT_POWER) != 0);
			default -> {
			}
		}
	}

	/** SkyCraft+ : client (a guest) -> server: the guest's Skyrim spells, shouts and arrows now (SkyMagic). */
	public record Shots(java.util.List<dev.skycraft.link.SkyLink.Shot> shots) implements CustomPacketPayload {
		public static final Type<Shots> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "shots"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Shots> CODEC = StreamCodec.of((buf, payload) -> {
			int count = Math.min(payload.shots().size(), 64);
			buf.writeVarInt(count);
			for (int i = 0; i < count; i++) {
				var s = payload.shots().get(i);
				buf.writeInt(s.id());
				buf.writeInt(s.flags());
				buf.writeFloat(s.x0());
				buf.writeFloat(s.y0());
				buf.writeFloat(s.z0());
				buf.writeFloat(s.x1());
				buf.writeFloat(s.y1());
				buf.writeFloat(s.z1());
				buf.writeFloat(s.radius());
				buf.writeFloat(s.damage());
				buf.writeFloat(s.push());
			}
		}, buf -> {
			int count = Math.min(buf.readVarInt(), 64);
			var list = new java.util.ArrayList<dev.skycraft.link.SkyLink.Shot>(count);
			for (int i = 0; i < count; i++) {
				list.add(new dev.skycraft.link.SkyLink.Shot(buf.readInt(), buf.readInt(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
					buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat()));
			}
			return new Shots(list);
		});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void init() {
		PayloadTypeRegistry.serverboundPlay().register(Shots.TYPE, Shots.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(Shots.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			// Within reason, whatever the guest's client claims (friends only, but still).
			var shots = payload.shots().stream().map(s -> new dev.skycraft.link.SkyLink.Shot(s.id(), s.flags(), s.x0(), s.y0(), s.z0(), s.x1(), s.y1(), s.z1(),
				Math.min(Math.max(s.radius(), 0.0F), 32.0F), Math.min(Math.max(s.damage(), 0.0F), 200.0F), Math.min(Math.max(s.push(), 0.0F), 10.0F))).toList();
			context.server().execute(() -> dev.skycraft.combat.SkyMagic.guestShots(player, shots));
		});
		PayloadTypeRegistry.serverboundPlay().register(Act.TYPE, Act.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(Act.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> apply(player, payload));
		});
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> GUEST_HANDS.remove(handler.player.getUUID()));
		PayloadTypeRegistry.serverboundPlay().register(Hurt.TYPE, Hurt.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DigOpen.TYPE, DigOpen.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DigReveal.TYPE, DigReveal.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(DigOpen.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> SkyDig.open(player, payload.world(), payload.pos(), payload.material()));
		});
		ServerPlayNetworking.registerGlobalReceiver(DigReveal.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			int[] materials = payload.materials().stream().mapToInt(Integer::intValue).toArray();
			context.server().execute(() -> SkyDig.reveal(player, payload.world(), payload.cells(), materials));
		});
		PayloadTypeRegistry.clientboundPlay().register(Died.TYPE, Died.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(Hurt.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			// A hit's worth of damage, whatever the guest's client claims (friends only, but still).
			float damage = Math.max(0.0F, Math.min(payload.skyrimDamage(), 10000.0F));
			context.server().execute(() -> SkyCombat.hurtPlayer(player, payload.kind(), damage, payload.attackerFormId(), payload.flags()));
		});
	}

	/** True if this player plays on this machine (their Skyrim is on the shared-memory link). */
	public static boolean isHost(ServerPlayer player) {
		var server = player.level().getServer();
		return server != null && server.isSingleplayerOwner(player.nameAndId());
	}
}
