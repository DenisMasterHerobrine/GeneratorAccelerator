package dev.denismasterherobrine.generatoraccelerator2.chunkload;

import com.google.common.collect.MapMaker;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;

/**
 * Reads a saved chunk's points of interest together with the chunk.
 *
 * <p>{@code ChunkSerializer.read} runs on the server thread. It checks each section against the dimension's POI storage.
 * The first check of a column that storage does not hold yet reads the column from disk and waits for it there
 * ({@code SectionStorage.readColumn}). Here the read starts on the worker pool as soon as the chunk's tag is read. The
 * load's server thread stage waits for both reads. {@code readColumn} then gets the finished read in place of a new one.
 * The vanilla code after the read runs as before, with the same error handling.
 *
 * <p>Exactness: the storage holds a column it has read, and writes only columns it holds. A column read ahead is
 * therefore not written before {@code readColumn} takes the read. A write queued before the read ahead is part of it,
 * because the IO worker answers a read with a write it still holds. A chunk is unloaded and its columns flushed in one
 * task on the server thread, before a later load of the chunk can begin. A read ahead is used once, and only inside the
 * {@code read} of the chunk tag it was made for. A tag that never reaches {@code read} takes its read ahead with it
 * (weak keys).
 */
public final class PoiPrefetch {
    private PoiPrefetch() {}

    private static final HookTargets.Hook HOOK = HookTargets.hook(HookTargets.CHUNK_POI_PREFETCH);

    private record Pending(Object storage, ChunkPos pos, CompletableFuture<Optional<CompoundTag>> read) {}

    /** By the chunk tag the read was made for, by identity. */
    private static final Map<CompoundTag, Pending> BY_TAG = new MapMaker().weakKeys().makeMap();
    /** The read ahead of the chunk the thread's {@code ChunkSerializer.read} loads. */
    private static final ThreadLocal<Pending> CURRENT = new ThreadLocal<>();

    public static boolean enabled() {
        return !ChunkLoadConfig.otherChunkSystem() && ChunkLoadConfig.poiPrefetch() && HOOK.allowed();
    }

    /** Starts reading the POI column of {@code pos} for the load of {@code chunkTag}. The future completes when the read has. */
    public static CompletableFuture<?> start(Object poiStorage, IOWorker worker, ChunkPos pos, CompoundTag chunkTag) {
        CompletableFuture<Optional<CompoundTag>> read = worker.loadAsync(pos);
        BY_TAG.put(chunkTag, new Pending(poiStorage, pos, read));
        ChunkLoadStats.POI_PREFETCHED.increment();
        return read;
    }

    /** At the start of {@code ChunkSerializer.read}. */
    public static void enter(Object poiStorage, ChunkPos pos, CompoundTag chunkTag) {
        Pending p = BY_TAG.remove(chunkTag);
        CURRENT.set(p != null && p.storage() == poiStorage && p.pos().equals(pos) ? p : null);
    }

    /** At the end of {@code ChunkSerializer.read}. */
    public static void exit() {
        CURRENT.set(null);
    }

    /** The read ahead of {@code pos} in {@code storage}, once, or null. */
    public static CompletableFuture<Optional<CompoundTag>> take(Object storage, ChunkPos pos) {
        Pending p = CURRENT.get();
        if (p == null || p.storage() != storage || !p.pos().equals(pos)) {
            return null;
        }
        CURRENT.set(null);
        ChunkLoadStats.POI_PREFETCH_USED.increment();
        return p.read();
    }
}
