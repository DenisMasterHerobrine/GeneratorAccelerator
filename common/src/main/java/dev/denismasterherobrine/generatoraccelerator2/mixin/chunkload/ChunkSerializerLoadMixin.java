package dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload;

import dev.denismasterherobrine.generatoraccelerator2.chunkload.ChunkLoadStats;
import dev.denismasterherobrine.generatoraccelerator2.chunkload.PoiPrefetch;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hands a load its POI read ahead ({@link PoiPrefetch}), times the load and notes chunks read back soon after their unload. */
@Mixin(ChunkSerializer.class)
public abstract class ChunkSerializerLoadMixin {
    @Unique
    private static final ThreadLocal<long[]> GA2$START = ThreadLocal.withInitial(() -> new long[1]);

    @Inject(method = "read", at = @At("HEAD"))
    private static void ga2$enterLoad(ServerLevel level, PoiManager poi, RegionStorageInfo info, ChunkPos pos, CompoundTag tag,
        CallbackInfoReturnable<ProtoChunk> cir) {
        PoiPrefetch.enter(poi, pos, tag);
        ChunkLoadStats.loadedFromDisk(level, pos);
        GA2$START.get()[0] = System.nanoTime();
    }

    @Inject(method = "read", at = @At("RETURN"))
    private static void ga2$exitLoad(ServerLevel level, PoiManager poi, RegionStorageInfo info, ChunkPos pos, CompoundTag tag,
        CallbackInfoReturnable<ProtoChunk> cir) {
        PoiPrefetch.exit();
        ChunkLoadStats.DISK_LOAD.record(System.nanoTime() - GA2$START.get()[0]);
    }
}
