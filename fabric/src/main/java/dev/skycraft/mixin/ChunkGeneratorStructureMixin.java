// SkyCraft+ : the Minecraft underground under Skyrim
package dev.skycraft.mixin;

import dev.skycraft.world.SkyUnderground;
import java.util.Set;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The underground's hidden world keeps only what's underground: its surface structures (ruined
 * portals, villages, wrecks...) came up as you dug under Skyrim's land, a hidden portal's netherrack
 * showing block by block. Skyrim's surface has Minecraft's own (SkyStructures).
 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorStructureMixin {
	@Unique
	private static final Set<String> UNDERGROUND_ONES = Set.of("minecraft:mineshaft", "minecraft:mineshaft_mesa", "minecraft:stronghold",
		"minecraft:ancient_city", "minecraft:trial_chambers");

	@Inject(method = "tryGenerateStructure", at = @At("HEAD"), cancellable = true)
	private void skycraft$undergroundOnly(StructureSet.StructureSelectionEntry selected, net.minecraft.world.level.StructureManager structureManager,
		net.minecraft.core.RegistryAccess registryAccess, net.minecraft.world.level.levelgen.RandomState randomState,
		net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager structureTemplateManager, long seed,
		net.minecraft.world.level.chunk.ChunkAccess centerChunk, net.minecraft.world.level.ChunkPos sourceChunkPos, ResourceKey<Level> level,
		net.minecraft.world.level.biome.Climate.Sampler climateSampler, CallbackInfoReturnable<Boolean> cir) {
		if (SkyUnderground.UNDERGROUND.equals(level)
			&& !selected.structure().unwrapKey().map(key -> UNDERGROUND_ONES.contains(key.identifier().toString())).orElse(false)) {
			cir.setReturnValue(false);
		}
	}
}
