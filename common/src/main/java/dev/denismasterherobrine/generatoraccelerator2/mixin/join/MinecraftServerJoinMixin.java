package dev.denismasterherobrine.generatoraccelerator2.mixin.join;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.denismasterherobrine.generatoraccelerator2.join.JoinWait;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.storage.ServerLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A new world's spawn search starts generating the area around its first candidate ({@link JoinWait#spawnCandidate}). */
@Mixin(value = MinecraftServer.class, priority = 5000)
public abstract class MinecraftServerJoinMixin {
    @WrapOperation(method = "setInitialSpawn", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/biome/Climate$Sampler;findSpawnPosition()Lnet/minecraft/core/BlockPos;"))
    private static BlockPos ga2$spawnCandidate(Climate.Sampler sampler, Operation<BlockPos> original, @Local(argsOnly = true) ServerLevel level) {
        BlockPos candidate = original.call(sampler);
        JoinWait.spawnCandidate(level, candidate);
        return candidate;
    }

    @Inject(method = "setInitialSpawn", at = @At("RETURN"))
    private static void ga2$spawnFound(ServerLevel level, ServerLevelData data, boolean bonusChest, boolean debug, LevelLoadListener listener,
                                       CallbackInfo ci) {
        JoinWait.spawnFound();
    }
}
