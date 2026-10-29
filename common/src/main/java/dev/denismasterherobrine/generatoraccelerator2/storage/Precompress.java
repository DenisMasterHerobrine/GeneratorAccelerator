package dev.denismasterherobrine.generatoraccelerator2.storage;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;
import net.minecraft.util.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

/**
 * Compressing saved chunks on the worker pool instead of the region file's IO thread.
 *
 * <p>Each storage (chunks, entities, points of interest) writes its region files on one IO thread. For chunks, compressing
 * the NBT is nearly all of that thread's work, about a millisecond per chunk. This caps saving, and with it pregeneration,
 * at about a thousand chunks a second. When a chunk is handed to the IO worker, its tag is final. Vanilla never changes
 * the tag again, and reads get copies. The tag is therefore compressed right away on the worker pool, with the same
 * encoder the IO thread would use ({@link FastRegionWrite}). When the IO thread comes to write that exact tag, it takes
 * the finished bytes. If a worker is compressing them right now, the IO thread waits for that worker. If the task has not
 * started, the IO thread takes the task back and compresses the tag itself. The IO thread thus never waits behind queued
 * work, and no compression is done twice. If a newer save of the same chunk replaces a tag, the old result stays unused.
 * The bytes are identical either way.
 *
 * <p>At most {@link #MAX_IN_FLIGHT} compressions wait on the pool at a time. A burst of saves (autosave,
 * {@code /save-all}) therefore cannot queue thousands of them ahead of chunk generation. Beyond that limit the IO thread
 * compresses, as vanilla does. Every write of a chunk settles the chunk's entry. A storage whose tags keep not matching
 * stops submitting, e.g. when another mod copies the tags on the way. No tag is held longer than its write.
 *
 * <p>{@code -Dgeneratoraccelerator2.precompress=false} turns it off.
 */
public final class Precompress {
    /** Not final so tests can compare both ways. */
    public static boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.precompress"));

    public static final LongAdder USED = new LongAdder();
    public static final LongAdder INLINE = new LongAdder();
    /** Pool tasks the IO thread took back before they started. The IO thread compressed the tag itself, so no work is lost. */
    public static final LongAdder SKIPPED = new LongAdder();
    /** Writes that waited for a pool task already compressing their tag. */
    public static final LongAdder WAITED = new LongAdder();

    private static final int QUEUED = 0;
    private static final int RUNNING = 1;
    private static final int TAKEN_BACK = 2;

    /** Compressions that may wait on the pool at once, across all storages. */
    static final int MAX_IN_FLIGHT = Math.max(8, 4 * Runtime.getRuntime().availableProcessors());
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    /** Writes in a row that found a different tag than the one submitted, after which a storage stops submitting. */
    private static final int MAX_MISMATCHES = 256;

    /** One compression on the pool. It starts queued, then either runs (the writer waits for it) or is taken back (skipped). */
    private static final class Job implements Runnable {
        /** Cleared when the job is taken back, so a skipped job does not keep the chunk's tag while it waits on the pool. */
        volatile CompoundTag tag;
        final AtomicInteger state = new AtomicInteger(QUEUED);
        final CompletableFuture<byte[]> bytes = new CompletableFuture<>();

        Job(CompoundTag tag) {
            this.tag = tag;
        }

        @Override
        public void run() {
            if (!this.state.compareAndSet(QUEUED, RUNNING)) {
                SKIPPED.increment();
                return;
            }
            try {
                ByteBuffer buffer = FastRegionWrite.encode(this.tag, RegionFileVersion.VERSION_DEFLATE.getId());
                this.bytes.complete(Arrays.copyOf(buffer.array(), buffer.limit()));
            } catch (Throwable e) {
                this.bytes.completeExceptionally(e);
            } finally {
                // The tag stays for the writer's identity check. The IO worker holds the tag until then anyway.
                IN_FLIGHT.decrementAndGet();
            }
        }

        /** Takes the job back before it starts. Returns true when the job will not run. Its slot is then freed at once. */
        boolean takeBack() {
            if (this.state.compareAndSet(QUEUED, TAKEN_BACK)) {
                this.tag = null;
                IN_FLIGHT.decrementAndGet();
                return true;
            }
            return false;
        }
    }

    private final ConcurrentHashMap<Long, Job> pending = new ConcurrentHashMap<>();

    /**
     * A chunk's key: its position, scrambled by a bijection. {@code Long.hashCode} of {@code x | z << 32} is {@code x ^ z}. That
     * hash is equal along whole diagonals. Plain positions would pile into a few bins, which the map turns into locked trees.
     */
    private static long key(ChunkPos pos) {
        return pos.pack() * 0x9E3779B97F4A7C15L;
    }
    private final AtomicInteger mismatches = new AtomicInteger();
    private volatile boolean stopped;

    public static boolean enabled() {
        return ENABLED && StorageConfig.precompress() && FastRegionWrite.enabled() && HookTargets.allowed(HookTargets.STORAGE_PRECOMPRESS);
    }

    /** Starts compressing a tag the IO worker was just given for this chunk. Deflate format only. */
    public void submit(ChunkPos pos, CompoundTag tag) {
        if (this.stopped || RegionFileVersion.getSelected() != RegionFileVersion.VERSION_DEFLATE) {
            return;
        }
        if (IN_FLIGHT.incrementAndGet() > MAX_IN_FLIGHT) {
            // The IO thread compresses this tag. An older submission for the chunk would hold the wrong tag now.
            IN_FLIGHT.decrementAndGet();
            discard(pos);
            return;
        }
        Job job = new Job(tag);
        Job replaced = this.pending.put(key(pos), job);
        if (replaced != null) {
            replaced.takeBack();
        }
        try {
            Util.backgroundExecutor().execute(job);
        } catch (RejectedExecutionException e) {
            this.pending.remove(key(pos), job);
            job.takeBack();
        }
    }

    /** Forgets the chunk's submission, if any. The chunk's write does not use a submission. */
    public void discard(ChunkPos pos) {
        Job job = this.pending.remove(key(pos));
        if (job != null) {
            job.takeBack();
        }
    }

    /**
     * Returns the compressed buffer for writing this tag, if it is the tag submitted last for this chunk. The buffer is the
     * pool's result. If a worker is compressing the tag right now, this waits for it. Returns null when the task had not
     * started. The task is then taken back and the caller compresses. Also returns null for any other tag.
     */
    @Nullable
    public ByteBuffer take(ChunkPos pos, CompoundTag tag) {
        Job job = this.pending.get(key(pos));
        if (job == null) {
            INLINE.increment();
            return null;
        }
        if (job.tag != tag) {
            // Usually a newer save of the chunk, written next. If tags never match, some mod copies them on the way. Stop then.
            if (this.mismatches.incrementAndGet() > MAX_MISMATCHES && !this.stopped) {
                this.stopped = true;
                clear();
            }
            // Dropped either way. A chunk whose tags some mod copies is not written again. Its job, with the whole tag and the
            // bytes, would stay for the rest of the run. If this was a newer save, that save compresses as before.
            if (this.pending.remove(key(pos), job)) {
                job.takeBack();
            }
            INLINE.increment();
            return null;
        }
        this.mismatches.set(0);
        this.pending.remove(key(pos), job);
        if (job.takeBack()) {
            INLINE.increment();
            return null;
        }
        if (!job.bytes.isDone()) {
            WAITED.increment();
        }
        try {
            byte[] result = job.bytes.join();
            USED.increment();
            return ByteBuffer.wrap(result);
        } catch (RuntimeException e) {
            INLINE.increment();
            return null;
        }
    }

    /** Drops results nobody will write (storage closed). */
    public void clear() {
        for (Long pos : this.pending.keySet()) {
            Job job = this.pending.remove(pos);
            if (job != null) {
                job.takeBack();
            }
        }
    }
}
