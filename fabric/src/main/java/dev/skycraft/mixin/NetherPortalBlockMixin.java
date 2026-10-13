// SkyCraft+ : the Nether and the End
package dev.skycraft.mixin;

import dev.skycraft.world.SkyMinecraftWorlds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.portal.TeleportTransition;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Back from the Nether, a player comes out of the portal they went in by, wherever their Nether
 * portal is. Minecraft only links portals within 128 blocks of the Nether position times eight;
 * further off it makes a new one, and the mirror world has no Minecraft ground to put it on, so it
 * hung in Skyrim's sky.
 */
@Mixin(NetherPortalBlock.class)
public abstract class NetherPortalBlockMixin {
	@Shadow
	private @Nullable TeleportTransition getExitPortal(ServerLevel newLevel, Entity entity, BlockPos portalEntryPos, BlockPos approximateExitPos, boolean toNether,
		WorldBorder worldBorder) {
		throw new AssertionError();
	}

	@Inject(method = "getPortalDestination", at = @At("HEAD"), cancellable = true)
	private void skycraft$backByThePortalTheyLeftBy(ServerLevel currentLevel, Entity entity, BlockPos portalEntryPos, CallbackInfoReturnable<TeleportTransition> cir) {
		if (currentLevel.dimension() != Level.NETHER || !(entity instanceof ServerPlayer player)) {
			return;
		}
		// SkyCraft+ : back into the same place: the open world, or the interior (or worldspace) the portal was built in.
		ServerLevel overworld = SkyMinecraftWorlds.leftLevel(player);
		BlockPos portal = overworld == null ? null : SkyMinecraftWorlds.portalLeftBy(player, overworld);
		if (portal == null) {
			TeleportTransition there = overworld == null ? null : SkyMinecraftWorlds.whereTheyLeft(player, overworld);
			if (there != null) {
				cir.setReturnValue(there);
			}
			return;
		}
		TeleportTransition back = this.getExitPortal(overworld, entity, portalEntryPos, portal, false, overworld.getWorldBorder());
		if (back != null) {
			cir.setReturnValue(back);
		}
	}
}
