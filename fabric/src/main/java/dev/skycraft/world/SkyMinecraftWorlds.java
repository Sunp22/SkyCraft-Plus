// SkyCraft+ : the Nether and the End
package dev.skycraft.world;

import dev.skycraft.SkyCraft;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Coming back to Skyrim from Minecraft's own worlds. Minecraft sends a player who leaves the End,
 * or dies in the Nether or the End, to their bed or else the world's spawn, which in the mirror
 * world means nothing (anywhere in Tamriel, in the air or in the ground). Out of the End's exit
 * portal (always) or after a death with no bed, they come back to the portal they left Skyrim's
 * open world by instead: on top of an End portal's frame (not into the lava under it, or back
 * through it), or beside a Nether portal.
 */
public final class SkyMinecraftWorlds {
	/** Where the player last left Skyrim's open world for a world of Minecraft's own. */
	public static final AttachmentType<BlockPos> LEFT_AT = AttachmentRegistry.<BlockPos>builder()
		.persistent(BlockPos.CODEC)
		.copyOnDeath()
		.buildAndRegister(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "left_open_world_at"));

	/** Each player's last position in the open world (the portal they step into). */
	private static final Map<UUID, BlockPos> LAST = new HashMap<>();

	private SkyMinecraftWorlds() {
	}

	public static void init() {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server -> LAST.clear());
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (player.level().dimension() == Level.OVERWORLD) {
					LAST.put(player.getUUID(), player.blockPosition());
				}
			}
		});
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) -> {
			BlockPos at = LAST.get(player.getUUID());
			if (origin.dimension() == Level.OVERWORLD && SkyDig.minecraftsOwnWorld(destination) && at != null) {
				player.setAttached(LEFT_AT, at);
				SkyCraft.LOG.info("SkyCraft: left the open world at {} for {}", at.toShortString(), destination.dimension().identifier());
			}
		});
	}

	/**
	 * Where a player coming back without a bed goes instead of the world spawn, or null to leave
	 * Minecraft's choice alone (a bed, or not coming from a world of Minecraft's own).
	 */
	public static @Nullable TeleportTransition comingBack(ServerPlayer player, TeleportTransition vanilla) {
		if (!SkyDig.minecraftsOwnWorld(player.level())) {
			return null;
		}
		// The End's exit portal always leads back to the stronghold's portal (the player asked for
		// that); a death goes to the bed if there is one, as in Minecraft.
		boolean leavingTheEnd = player.isAlive() && player.level().dimension() == Level.END;
		if (!leavingTheEnd && player.getRespawnConfig() != null && !vanilla.missingRespawnBlock()) {
			return null;
		}
		BlockPos at = player.getAttached(LEFT_AT);
		ServerLevel overworld = player.level().getServer().overworld();
		if (at == null || overworld == null) {
			return null;
		}
		Vec3 spot = safeSpot(overworld, at);
		SkyCraft.LOG.info("SkyCraft: back from {} to the portal at {} ({})", player.level().dimension().identifier(), at.toShortString(), spot);
		return new TeleportTransition(overworld, spot, Vec3.ZERO, player.getYRot(), 0.0F, vanilla.postTeleportTransition());
	}

	/** The Nether portal the player left the open world by, if it's still there (NetherPortalBlockMixin). */
	public static @Nullable BlockPos portalLeftBy(ServerPlayer player, ServerLevel overworld) {
		BlockPos at = player.getAttached(LEFT_AT);
		if (at == null) {
			return null;
		}
		for (BlockPos q : BlockPos.betweenClosed(at.offset(-2, -1, -2), at.offset(2, 3, 2))) {
			if (overworld.getBlockState(q).is(Blocks.NETHER_PORTAL)) {
				SkyCraft.LOG.info("SkyCraft: back from the Nether by the portal at {}", q.toShortString());
				return q.immutable();
			}
		}
		return null;
	}

	/**
	 * Back from the Nether when the portal they left by is gone: where it stood, not a new portal (the
	 * mirror world has no Minecraft ground there, and one was made hanging in Skyrim's sky). Null
	 * if they didn't leave the open world by a portal.
	 */
	public static @Nullable TeleportTransition whereTheyLeft(ServerPlayer player, ServerLevel overworld) {
		BlockPos at = player.getAttached(LEFT_AT);
		if (at == null) {
			return null;
		}
		Vec3 spot = safeSpot(overworld, at);
		SkyCraft.LOG.info("SkyCraft: back from the Nether to where its portal was, {} (it's gone)", at.toShortString());
		return new TeleportTransition(overworld, spot, Vec3.ZERO, player.getYRot(), 0.0F, TeleportTransition.DO_NOTHING);
	}

	/** On top of the nearest End portal frame; else beside the Nether portal; else where they left. */
	private static Vec3 safeSpot(ServerLevel level, BlockPos at) {
		BlockPos frame = null;
		double best = Double.MAX_VALUE;
		for (BlockPos q : BlockPos.betweenClosed(at.offset(-4, -2, -4), at.offset(4, 2, 4))) {
			if (level.getBlockState(q).is(Blocks.END_PORTAL_FRAME)) {
				double d = q.distSqr(at);
				if (d < best) {
					best = d;
					frame = q.immutable();
				}
			}
		}
		if (frame != null) {
			return new Vec3(frame.getX() + 0.5, frame.getY() + 13.0 / 16.0, frame.getZ() + 0.5);
		}
		BlockState here = level.getBlockState(at);
		if (here.is(Blocks.NETHER_PORTAL)) {
			// Out of the portal's plane, to whichever side is open.
			Direction.Axis axis = here.getValue(BlockStateProperties.HORIZONTAL_AXIS);
			Direction side = axis == Direction.Axis.X ? Direction.SOUTH : Direction.EAST;
			for (Direction d : new Direction[] { side, side.getOpposite() }) {
				BlockPos q = at.relative(d);
				if (level.getBlockState(q).getCollisionShape(level, q).isEmpty() && level.getBlockState(q.above()).getCollisionShape(level, q.above()).isEmpty()) {
					return Vec3.atBottomCenterOf(q);
				}
			}
		}
		return Vec3.atBottomCenterOf(at);
	}
}
