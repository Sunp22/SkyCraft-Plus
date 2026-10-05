// SkyCraft+ : Skyrim magic, arrows and shouts on Minecraft mobs
package dev.skycraft.combat;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The Skyrim player's spells, shouts and arrows on Minecraft's mobs. Skyrim lists its projectiles
 * each frame (SkyrimShots in the protocol), each with its whole path so far; every server tick
 * this hits the mobs each one reaches, once per shot: damage as the player's own (drops and
 * experience), fire (a mob that dies burning drops cooked meat, as with Fire Aspect), frost slows,
 * Unrelenting Force and the like throw them back. Skyrim's NPCs aren't touched: Skyrim hits those.
 */
public final class SkyMagic {
	private static final List<SkyLink.Shot> SHOTS = new ArrayList<>();
	/** Shot id -> the mobs it has hit (a stopping shot that hit something holds STOPPED). */
	private static final Map<Integer, Set<Integer>> HIT = new HashMap<>();
	private static final int STOPPED = Integer.MIN_VALUE;
	private static int logged;
	private static int readsLogged;

	private SkyMagic() {
	}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(SkyMagic::tick);
	}

	private static void tick(MinecraftServer server) {
		if (!SkyLink.active() || !SkyLink.readSkyrimShots(SHOTS)) {
			return;
		}
		Set<Integer> live = new HashSet<>();
		for (SkyLink.Shot shot : SHOTS) {
			live.add(shot.id());
		}
		HIT.keySet().retainAll(live);
		if (SHOTS.isEmpty()) {
			return;
		}
		if (readsLogged < 5) {
			readsLogged++;
			StringBuilder list = new StringBuilder();
			for (SkyLink.Shot shot : SHOTS) {
				list.append(String.format(" [%x flags %x %.1f,%.1f,%.1f -> %.1f,%.1f,%.1f r %.2f dmg %.2f]", shot.id(), shot.flags(), shot.x0(), shot.y0(),
					shot.z0(), shot.x1(), shot.y1(), shot.z1(), shot.radius(), shot.damage()));
			}
			SkyCraft.LOG.info("SkyCraft: Skyrim shots:{}", list);
		}
		ServerPlayer host = null;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (dev.skycraft.net.SkyNet.isHost(player)) {
				host = player;
				break;
			}
		}
		if (host == null) {
			return;
		}
		for (SkyLink.Shot shot : SHOTS) {
			apply(host, shot);
		}
	}

	private static void apply(ServerPlayer player, SkyLink.Shot shot) {
		Set<Integer> hit = HIT.computeIfAbsent(shot.id(), k -> new HashSet<>());
		if (hit.contains(STOPPED)) {
			return;
		}
		ServerLevel level = player.level();
		Vec3 a = new Vec3(shot.x0(), shot.y0(), shot.z0());
		Vec3 b = new Vec3(shot.x1(), shot.y1(), shot.z1());
		int flags = shot.flags();
		double reach = (flags & Proto.SHOT_CONE) != 0 ? 0.0 : shot.radius();
		AABB area = new AABB(a, b).inflate((flags & (Proto.SHOT_CONE | Proto.SHOT_EXPLOSION)) != 0 ? Math.max(shot.radius(), a.distanceTo(b)) + 1.0 : reach + 1.0);
		List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class, area,
			e -> e.isAlive() && !(e instanceof SkyrimActorEntity) && !(e instanceof Player) && !hit.contains(e.getId()));
		if (candidates.isEmpty()) {
			return;
		}
		List<LivingEntity> targets = new ArrayList<>();
		if ((flags & Proto.SHOT_EXPLOSION) != 0) {
			for (LivingEntity e : candidates) {
				if (e.getBoundingBox().getCenter().distanceTo(b) <= shot.radius() + e.getBbWidth() * 0.5) {
					targets.add(e);
				}
			}
		} else if ((flags & Proto.SHOT_CONE) != 0) {
			Vec3 axis = b.subtract(a);
			double length = axis.length();
			if (length < 0.1) {
				return;
			}
			axis = axis.scale(1.0 / length);
			for (LivingEntity e : candidates) {
				Vec3 to = e.getBoundingBox().getCenter().subtract(a);
				double distance = to.length();
				if (distance <= length + e.getBbWidth() && distance > 0.01 && Math.acos(Math.max(-1.0, Math.min(1.0, to.dot(axis) / distance))) <= shot.radius()) {
					targets.add(e);
				}
			}
		} else {
			// A path or a beam: what its swept width passes through, nearest first.
			LivingEntity nearest = null;
			double best = Double.MAX_VALUE;
			for (LivingEntity e : candidates) {
				AABB box = e.getBoundingBox().inflate(reach);
				var at = box.contains(a) ? java.util.Optional.of(a) : box.clip(a, b);
				if (at.isEmpty()) {
					continue;
				}
				double d = at.get().distanceTo(a);
				if ((flags & (Proto.SHOT_STOPS | Proto.SHOT_BEAM)) != 0) {
					if (d < best) {
						best = d;
						nearest = e;
					}
				} else {
					targets.add(e);
				}
			}
			if (nearest != null) {
				targets.add(nearest);
				if ((flags & Proto.SHOT_STOPS) != 0) {
					hit.add(STOPPED);
				}
			}
		}
		for (LivingEntity e : targets) {
			hit.add(e.getId());
			affect(level, player, e, shot, a);
		}
	}

	private static void affect(ServerLevel level, ServerPlayer player, LivingEntity e, SkyLink.Shot shot, Vec3 from) {
		int flags = shot.flags();
		if ((flags & Proto.SHOT_FIRE) != 0) {
			e.setRemainingFireTicks(Math.max(e.getRemainingFireTicks(), 100));
		}
		if ((flags & Proto.SHOT_FROST) != 0) {
			e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 80, 1), player);
		}
		if (shot.damage() > 0.0F) {
			e.hurtServer(level, player.damageSources().indirectMagic(player, player), shot.damage());
		}
		if ((flags & Proto.SHOT_PUSH) != 0 && shot.push() > 0.0F) {
			Vec3 away = e.position().subtract(from);
			double h = Math.sqrt(away.x * away.x + away.z * away.z);
			if (h > 1.0e-3) {
				e.knockback(shot.push(), -away.x / h, -away.z / h, player.damageSources().playerAttack(player), shot.damage());
				e.setDeltaMovement(e.getDeltaMovement().add(0.0, 0.25 * shot.push(), 0.0));
			}
		}
		if (logged < 10) {
			logged++;
			SkyCraft.LOG.info("SkyCraft: a Skyrim {} hit a {} (damage {}{}{}{})", describe(flags), e.getType().toShortString(), shot.damage(),
				(flags & Proto.SHOT_FIRE) != 0 ? ", fire" : "", (flags & Proto.SHOT_FROST) != 0 ? ", frost" : "",
				(flags & Proto.SHOT_PUSH) != 0 ? ", push " + shot.push() : "");
		}
	}

	private static String describe(int flags) {
		if ((flags & Proto.SHOT_EXPLOSION) != 0) {
			return "explosion";
		}
		if ((flags & Proto.SHOT_CONE) != 0) {
			return "cone (shout)";
		}
		if ((flags & Proto.SHOT_BEAM) != 0) {
			return "beam";
		}
		return (flags & Proto.SHOT_STOPS) != 0 ? "bolt or arrow" : "stream";
	}
}
