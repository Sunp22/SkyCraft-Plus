// SkyCraft+ : hand ownership
package dev.skycraft.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;

/**
 * Which of the player's hands Skyrim holds: equipping a Skyrim weapon, spell, shield or torch in
 * a hand (from Skyrim's inventory) makes it Skyrim's until it's unequipped. Minecraft then neither
 * draws that hand nor uses its item, and Skyrim gets that hand's mouse button.
 */
public final class SkyHands {
	private SkyHands() {
	}

	public static boolean skyrimOwns(HumanoidArm arm) {
		if (!SkyClient.linked()) {
			return false;
		}
		var sky = SkyClient.sky();
		return arm == HumanoidArm.RIGHT ? sky.rightHandSkyrim() : sky.leftHandSkyrim();
	}

	/**
	 * The crosshair is on something used by hand: a block with a screen (chest, crafting table,
	 * furnace...), a door, trapdoor, gate, button, lever or bed, or a villager, horse, boat,
	 * minecart or item frame. Right click then goes to Minecraft even with a Skyrim left hand.
	 */
	public static boolean usableTargetInView(Minecraft minecraft) {
		var hit = minecraft.hitResult;
		var level = minecraft.level;
		if (hit == null || level == null) {
			return false;
		}
		if (hit instanceof net.minecraft.world.phys.EntityHitResult entityHit) {
			var e = entityHit.getEntity();
			return e instanceof net.minecraft.world.entity.npc.villager.AbstractVillager || e instanceof net.minecraft.world.entity.animal.equine.AbstractHorse
				|| e instanceof net.minecraft.world.entity.vehicle.VehicleEntity || e instanceof net.minecraft.world.entity.decoration.ItemFrame;
		}
		if (hit instanceof net.minecraft.world.phys.BlockHitResult blockHit && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
			var pos = blockHit.getBlockPos();
			var state = level.getBlockState(pos);
			var block = state.getBlock();
			return state.getMenuProvider(level, pos) != null || block instanceof net.minecraft.world.level.block.DoorBlock
				|| block instanceof net.minecraft.world.level.block.TrapDoorBlock || block instanceof net.minecraft.world.level.block.FenceGateBlock
				|| block instanceof net.minecraft.world.level.block.ButtonBlock || block instanceof net.minecraft.world.level.block.LeverBlock
				|| block instanceof net.minecraft.world.level.block.BedBlock;
		}
		return false;
	}

	public static boolean skyrimOwns(InteractionHand hand) {
		var player = Minecraft.getInstance().player;
		if (player == null) {
			return false;
		}
		HumanoidArm main = player.getMainArm();
		return skyrimOwns(hand == InteractionHand.MAIN_HAND ? main : main.getOpposite());
	}
}
