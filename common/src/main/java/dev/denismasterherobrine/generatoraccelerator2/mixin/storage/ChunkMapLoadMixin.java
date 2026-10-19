package dev.denismasterherobrine.generatoraccelerator2.mixin.storage;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.storage.LoadPrep;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Prepares chunks loaded for the chunk system ({@code scheduleChunkLoad}) on the worker pool ({@link LoadPrep}). Preparation
 * runs after the chunk tag is read and upgraded, and before the server thread reads the tag. Other chunk reads are left
 * alone, e.g. {@code isExistingChunkFull}, which the server thread waits for.
 */
@Mixin(value = ChunkMap.class, priority = 5000)
public abstract class ChunkMapLoadMixin {
    @Shadow @Final ServerLevel level;

    @WrapOperation(method = "scheduleChunkLoad", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ChunkMap;readChunk(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Optional<CompoundTag>> ga2$prepareLoaded(ChunkMap map, ChunkPos pos, Operation<CompletableFuture<Optional<CompoundTag>>> original) {
        CompletableFuture<Optional<CompoundTag>> read = original.call(map, pos);
        if (!LoadPrep.enabled()) {
            return read;
        }
        ServerLevel level = this.level;
        return read.thenCompose(tag -> {
            if (tag.isEmpty()) {
                // Nothing saved here, as for every chunk generated for the first time. No work and no extra task.
                return CompletableFuture.completedFuture(tag);
            }
            if (!level.getServer().isSameThread()) {
                // The read completes on the worker pool, in the upgrade stage of readChunk. Prepare on this thread.
                LoadPrep.prepare(level, pos, tag.get());
                return CompletableFuture.completedFuture(tag);
            }
            // The read completed before this stage was attached, so this runs on the server thread. Hand off to the pool.
            return CompletableFuture.supplyAsync(() -> {
                LoadPrep.prepare(level, pos, tag.get());
                return tag;
            }, Util.backgroundExecutor());
        });
    }
}
