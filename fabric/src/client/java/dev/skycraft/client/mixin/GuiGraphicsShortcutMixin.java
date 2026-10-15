// SkyCraft+ : Skyrim shortcuts
package dev.skycraft.client.mixin;

import dev.skycraft.client.SkyShortcutsClient;
import dev.skycraft.net.SkyShortcuts;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A Skyrim shortcut shows how many Skyrim has, and greys out when Skyrim has none left. */
@Mixin(GuiGraphicsExtractor.class)
public abstract class GuiGraphicsShortcutMixin {
	@ModifyVariable(method = "itemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V", at = @At("HEAD"), argsOnly = true)
	private @Nullable String skycraft$skyrimCount(@Nullable String countText, Font font, ItemStack stack, int x, int y, @Nullable String original) {
		return countText == null && SkyShortcuts.isShortcut(stack) ? SkyShortcutsClient.countText(stack) : countText;
	}

	@Inject(method = "itemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V", at = @At("TAIL"))
	private void skycraft$greyWhenGone(Font font, ItemStack stack, int x, int y, @Nullable String countText, CallbackInfo ci) {
		if (SkyShortcutsClient.gone(stack)) {
			((GuiGraphicsExtractor) (Object) this).fill(x, y, x + 16, y + 16, 0xA0303030);
		}
	}
}
