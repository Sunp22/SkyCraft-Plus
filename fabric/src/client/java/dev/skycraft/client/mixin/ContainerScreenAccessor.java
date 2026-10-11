// SkyCraft+ : controller inventory snapping
package dev.skycraft.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Where a container screen's slots are measured from (InputBridge.slotJump). */
@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenAccessor {
	@Accessor("leftPos")
	int skycraft$leftPos();

	@Accessor("topPos")
	int skycraft$topPos();
}
