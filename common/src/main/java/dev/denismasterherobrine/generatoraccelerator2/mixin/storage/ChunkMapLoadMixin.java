package dev.denismasterherobrine.generatoraccelerator2.mixin.storage;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.chunkload.PoiPrefetch;
import dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload.SectionStorageAccessor;
import dev.denismasterherobrine.generatoraccelerator2.storage.LoadPrep;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Prepares chunks loaded for the chunk system ({@code scheduleChunkLoad}) on the worker pool ({@link LoadPrep}). Preparation
 * runs after the chunk tag is read and upgraded, and before the server thread reads the tag. Other chunk reads are left
 * alone, e.g. {@code isExistingChunkFull}, which the server thread waits for.
 *
 * <p>Also starts reading the chunk's points of interest once its tag is read ({@link PoiPrefetch}). The server thread stage
 * of the load then waits for that read too.
 */
@Mixin(value = ChunkMap.class, priority = 5000)
public abstract class ChunkMapLoadMixin {
    @Shadow @Final ServerLevel level;
    @Shadow @Final private PoiManager poiManager;

    @WrapOperation(method = "scheduleChunkLoad", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ChunkMap;readChunk(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Optional<CompoundTag>> ga2$prepareLoaded(ChunkMap map, ChunkPos pos, Operation<CompletableFuture<Optional<CompoundTag>>> original) {
        CompletableFuture<Optional<CompoundTag>> read = original.call(map, pos);
        boolean prepare = LoadPrep.enabled();
        boolean poi = PoiPrefetch.enabled();
        if (!prepare && !poi) {
            return read;
        }
        ServerLevel level = this.level;
        PoiManager poiManager = this.poiManager;
        return read.thenCompose(tag -> {
            if (tag.isEmpty()) {
                // Nothing saved here, as for every chunk generated for the first time. No work and no extra task.
                return CompletableFuture.completedFuture(tag);
            }
            // The POI read starts first, so the disk reads it while the chunk is prepared.
            CompletableFuture<?> poiRead = poi
                ? PoiPrefetch.start(poiManager, ((SectionStorageAccessor) poiManager).ga2$simpleRegionStorage(), pos, tag.get()) : null;
            CompletableFuture<Optional<CompoundTag>> prepared;
            if (!prepare) {
                prepared = CompletableFuture.completedFuture(tag);
            } else if (!level.getServer().isSameThread()) {
                // The read completes on the worker pool, in the upgrade stage of readChunk. Prepare on this thread.
                LoadPrep.prepare(level, pos, tag.get());
                prepared = CompletableFuture.completedFuture(tag);
            } else {
                // The read completed before this stage was attached, so this runs on the server thread. Hand off to the pool.
                prepared = CompletableFuture.supplyAsync(() -> {
                    LoadPrep.prepare(level, pos, tag.get());
                    return tag;
                }, Util.backgroundExecutor());
            }
            // A failed POI read is reported where vanilla reports it, when the server thread takes it.
            return poiRead == null ? prepared : prepared.thenCombine(poiRead.handle((result, error) -> null), (t, ignored) -> t);
        });
    }
}
