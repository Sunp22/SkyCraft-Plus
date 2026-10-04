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

	public static boolean skyrimOwns(InteractionHand hand) {
		var player = Minecraft.getInstance().player;
		if (player == null) {
			return false;
		}
		HumanoidArm main = player.getMainArm();
		return skyrimOwns(hand == InteractionHand.MAIN_HAND ? main : main.getOpposite());
	}
}
