package dev.denismasterherobrine.generatoraccelerator2.mixin.join;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.denismasterherobrine.generatoraccelerator2.join.JoinWait;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.storage.ServerLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The spawn area a world waits for before players can join, and the areas that start loading early ({@link JoinWait}). */
@Mixin(value = MinecraftServer.class, priority = 5000)
public abstract class MinecraftServerJoinMixin {
    @Shadow public abstract ServerLevel overworld();

    @Shadow public abstract GameRules getGameRules();

    @Inject(method = "prepareLevels", at = @At("HEAD"))
    private void ga2$preparing(ChunkProgressListener listener, CallbackInfo ci) {
        JoinWait.preparing((MinecraftServer) (Object) this);
    }

    /**
     * Vanilla waits while fewer chunks tick than the spawn chunk radius's square holds. With a smaller area, the loop
     * instead runs until that area ticks around the spawn chunk.
     */
    @WrapOperation(method = "prepareLevels", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache;getTickingGenerated()I"))
    private int ga2$spawnArea(ServerChunkCache cache, Operation<Integer> original) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        int radius = JoinWait.radius(server);
        if (radius == JoinWait.vanillaRadius(this.getGameRules().getInt(GameRules.RULE_SPAWN_CHUNK_RADIUS))) {
            return original.call(cache);
        }
        ServerLevel overworld = this.overworld();
        return JoinWait.ready(overworld, new ChunkPos(overworld.getSharedSpawnPos()), radius) ? Integer.MAX_VALUE : Integer.MIN_VALUE;
    }

    @Inject(method = "prepareLevels", at = @At("TAIL"))
    private void ga2$prepared(ChunkProgressListener listener, CallbackInfo ci) {
        JoinWait.prepared((MinecraftServer) (Object) this);
    }

    @WrapOperation(method = "setInitialSpawn", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/biome/Climate$Sampler;findSpawnPosition()Lnet/minecraft/core/BlockPos;"))
    private static BlockPos ga2$spawnCandidate(Climate.Sampler sampler, Operation<BlockPos> original, @Local(argsOnly = true) ServerLevel level) {
        BlockPos candidate = original.call(sampler);
        JoinWait.spawnCandidate(level, candidate);
        return candidate;
    }

    @Inject(method = "setInitialSpawn", at = @At("RETURN"))
    private static void ga2$spawnFound(ServerLevel level, ServerLevelData data, boolean bonusChest, boolean debug, CallbackInfo ci) {
        JoinWait.spawnFound();
    }
}
