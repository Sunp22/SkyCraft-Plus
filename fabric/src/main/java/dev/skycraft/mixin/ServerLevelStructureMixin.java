// SkyCraft+ : the Minecraft underground under Skyrim
package dev.skycraft.mixin;

import dev.skycraft.world.SkyUnderground;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The mirror world has no structures; the underground under Skyrim's open world does (it comes up
 * from the hidden world as you dig). Eyes of ender, and explorer maps to structures that are down
 * there, look in the hidden world: same x and z. Surface structures (villages, mansions,
 * monuments) aren't asked for there, as they don't exist under Skyrim.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelStructureMixin {
	private static final Set<TagKey<Structure>> SKYCRAFT$UNDERGROUND = Set.of(StructureTags.EYE_OF_ENDER_LOCATED,
		StructureTags.ON_BURIED_TRIAL_CHAMBERS_MAPS, StructureTags.ON_ANCIENT_CITY_MAPS, StructureTags.ON_MINESHAFT_MAPS);

	@Inject(method = "findNearestMapStructure(Lnet/minecraft/tags/TagKey;Lnet/minecraft/core/BlockPos;IZ)Lnet/minecraft/core/BlockPos;",
		at = @At("HEAD"), cancellable = true)
	private void skycraft$underground(TagKey<Structure> tag, BlockPos origin, int radius, boolean createReference, CallbackInfoReturnable<BlockPos> cir) {
		if (!SKYCRAFT$UNDERGROUND.contains(tag)) {
			return;
		}
		ServerLevel hidden = SkyUnderground.hiddenUnder((ServerLevel) (Object) this);
		if (hidden != null) {
			cir.setReturnValue(hidden.findNearestMapStructure(tag, origin, radius, createReference));
		}
	}
}
