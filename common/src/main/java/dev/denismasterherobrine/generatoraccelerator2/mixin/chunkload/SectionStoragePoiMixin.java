package dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.chunkload.ChunkLoadStats;
import dev.denismasterherobrine.generatoraccelerator2.chunkload.PoiPrefetch;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A column's read takes the read made ahead with its chunk ({@link PoiPrefetch}). Vanilla's error handling in
 * {@code tryRead} applies to it as to its own read. Also counts the column reads and the waits for them.
 */
@Mixin(SectionStorage.class)
public abstract class SectionStoragePoiMixin {
    @WrapOperation(method = "tryRead", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/chunk/storage/SimpleRegionStorage;read(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Optional<CompoundTag>> ga2$readAhead(SimpleRegionStorage storage, ChunkPos pos,
        Operation<CompletableFuture<Optional<CompoundTag>>> original) {
        CompletableFuture<Optional<CompoundTag>> ahead = PoiPrefetch.take(this, pos);
        return ahead != null ? ahead : original.call(storage, pos);
    }

    @WrapOperation(method = "readColumn(Lnet/minecraft/world/level/ChunkPos;)V", at = @At(value = "INVOKE",
        target = "Ljava/util/concurrent/CompletableFuture;join()Ljava/lang/Object;"))
    private Object ga2$timeColumnRead(CompletableFuture<?> read, Operation<Object> original) {
        ChunkLoadStats.POI_READS.increment();
        if (read.isDone()) {
            return original.call(read);
        }
        long start = System.nanoTime();
        try {
            return original.call(read);
        } finally {
            ChunkLoadStats.POI_WAIT.record(System.nanoTime() - start);
        }
    }
}
