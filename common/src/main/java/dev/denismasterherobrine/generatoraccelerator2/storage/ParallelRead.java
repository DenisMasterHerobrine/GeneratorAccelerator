package dev.denismasterherobrine.generatoraccelerator2.storage;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import java.io.DataInputStream;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.util.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loading a saved chunk. Vanilla has one IO thread per region storage. That thread reads the chunk's sectors from the
 * region file, then decompresses and parses them into NBT. It handles one chunk after another, between its chunk writes,
 * so loads queue behind a single thread. With this class the IO thread still reads the bytes, in the same task and in the
 * same order relative to writes. A chunk waiting to be written is still copied from its pending write. The bytes are then
 * decompressed and parsed on the worker pool. The tag is the same. A failure is logged and handed on as vanilla's, with
 * the same logger, the same message and the same exception.
 *
 * <p>Only chunk and entity storage use this. The server thread waits for POI reads ({@code SectionStorage.getOrLoad} joins
 * them), and the worker pool may be busy generating chunks. A chunk stored outside the region file ({@code .mcc}) is read
 * from its own file while parsing. Such a chunk is therefore parsed on the IO thread as before.
 *
 * <p>{@code -Dgeneratoraccelerator2.parallelRead=false} turns it off.
 */
public final class ParallelRead {
    private ParallelRead() {}

    /** Not final so the tests can compare both ways. */
    public static boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.parallelRead"));
    /** IOWorker's own logger name, so failures read as vanilla's. */
    private static final Logger IO_LOGGER = LoggerFactory.getLogger(IOWorker.class);
    private static final HookTargets.Hook HOOK = HookTargets.hook(HookTargets.STORAGE_PARALLEL_READ);

    public static final LongAdder PARSED = new LongAdder();
    public static final LongAdder ON_IO_THREAD = new LongAdder();

    /** Set by {@code RegionFileReadMixin} when the stream {@code getChunkDataInputStream} returns reads an external file. */
    public static final ThreadLocal<boolean[]> EXTERNAL = ThreadLocal.withInitial(() -> new boolean[1]);

    public static boolean enabled() {
        return ENABLED && StorageConfig.parallelRead() && HOOK.allowed();
    }

    public static boolean appliesTo(String storageType) {
        return "chunk".equals(storageType) || "entities".equals(storageType);
    }

    /** A chunk's bytes, read by the IO thread, still to be decompressed and parsed. */
    public record Unparsed(DataInputStream stream) {}

    /** A read that failed on the IO thread. The load fails with the same exception as vanilla's. */
    public record Failed(Exception exception) {}

    /** Decompresses and parses {@code raw} on the worker pool and completes {@code out} with the tag or vanilla's failure. */
    public static void parseLater(Unparsed raw, ChunkPos pos, CompletableFuture<Optional<CompoundTag>> out) {
        Runnable task = () -> parse(raw, pos, out);
        try {
            Util.backgroundExecutor().execute(task);
        } catch (RejectedExecutionException e) {
            // The pool is shutting down. Parse here, as vanilla would have on the IO thread.
            task.run();
        }
    }

    private static void parse(Unparsed raw, ChunkPos pos, CompletableFuture<Optional<CompoundTag>> out) {
        CompoundTag tag;
        // As in RegionFileStorage.read, the stream is closed after reading, whatever happens.
        try (DataInputStream in = raw.stream()) {
            tag = NbtIo.read(in);
        } catch (Exception e) {
            IO_LOGGER.warn("Failed to read chunk {}", pos, e);
            out.completeExceptionally(e);
            return;
        } catch (Throwable t) {
            out.completeExceptionally(t);
            throw t;
        }
        PARSED.increment();
        out.complete(Optional.ofNullable(tag));
    }
}
