// SkyCraft+ : Skyrim poisons on Minecraft weapons
package dev.skycraft.client;

import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.net.SkyNet;
import dev.skycraft.net.SkyPoison;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** A Skyrim poison used with a Minecraft weapon in the right hand: onto that weapon (SkyPoison). */
public final class SkyPoisonClient {
	private SkyPoisonClient() {
	}

	/** True if the poison went onto the Minecraft weapon (else it's Skyrim's to apply, as ever). */
	public static boolean apply(SkyLink.SkyItem item) {
		Minecraft minecraft = Minecraft.getInstance();
		var player = minecraft.player;
		if (player == null || item.kind() != Proto.SKY_POTION || item.sub() != Proto.P_POISON || SkyClient.sky().rightHandSkyrim()
			|| !SkyPoison.poisonable(player.getMainHandItem())) {
			return false;
		}
		SkyLink.pushEvent(Proto.EV_SKY_ITEM, item.formId(), 0.0F, 0.0F, 0.0F, 0.0F, Proto.SKY_ACT_CONSUME);
		SkyClient.act(minecraft, SkyNet.Act.simple(SkyNet.Act.POISON, 0, SkyPoison.minecraftDamage(item.stat(), item.stat2()), 0.0F));
		player.sendOverlayMessage(Component.literal(item.name() + " on your " + player.getMainHandItem().getHoverName().getString()));
		return true;
	}
}
