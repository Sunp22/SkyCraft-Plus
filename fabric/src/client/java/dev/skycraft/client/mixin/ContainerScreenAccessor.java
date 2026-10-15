// SkyCraft+ : controller inventory snapping
package dev.skycraft.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Where a container screen's slots are measured from (InputBridge.slotJump). */
@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenAccessor {
	@Accessor("leftPos")
	int skycraft$leftPos();

	@Accessor("topPos")
	int skycraft$topPos();

	/** SkyCraft+ : Skyrim shortcuts. The slot under the mouse, where a Skyrim thing is dropped. */
	@Accessor("hoveredSlot")
	@Nullable Slot skycraft$hoveredSlot();
}
