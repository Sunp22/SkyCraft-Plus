// SkyCraft+ : the Nether and the End
package dev.skycraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.skycraft.world.SkyDig;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Natural spawning goes on in the Nether and the End, whatever the mob spawning game rule says (see ServerLevelSpawningMixin). */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheSpawningMixin {
	@Shadow
	@Final
	private ServerLevel level;

	@WrapOperation(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/gamerules/GameRules;get(Lnet/minecraft/world/level/gamerules/GameRule;)Ljava/lang/Object;"))
	private Object skycraft$vanillaInMinecraftsWorlds(GameRules rules, GameRule<?> rule, Operation<Object> original) {
		if (rule == GameRules.SPAWN_MOBS && SkyDig.minecraftsOwnWorld(this.level)) {
			return Boolean.TRUE;
		}
		return original.call(rules, rule);
	}
}
