package dev.denismasterherobrine.generatoraccelerator2.parallellight;

import dev.denismasterherobrine.generatoraccelerator2.parallellight.mixin.DataLayerStorageMapAccessor;
import dev.denismasterherobrine.generatoraccelerator2.parallellight.mixin.LayerLightSectionStorageAccessor;
import dev.denismasterherobrine.generatoraccelerator2.parallellight.mixin.LightEngineAccessor;
import dev.denismasterherobrine.generatoraccelerator2.parallellight.mixin.SkyLightSectionStorageAccessor;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntMaps;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;

/**
 * The decrease and increase propagation of one batch, split by section between the light thread and helper threads.
 *
 * <p>Each section belongs to one worker ({@link #owner}). A worker takes the queue entries of its own sections and follows
 * them through those sections. Steps that enter another worker's section are sent to that worker ({@link Propagator}).
 * All decreases finish before any increase starts, as in vanilla. A phase ends when no worker has work and no batch of
 * steps is in flight. {@link #outstanding} counts workers at work plus batches sent and not yet taken.
 *
 * <p>During a batch, the key set of the section map does not change. Sections come and go only in the light tasks and at
 * the end of the batch. Workers may therefore look up the presence of any section. The light data of a section is read
 * and written by its owner only. A section's data is copied before its first write in a batch, exactly as
 * {@code setStoredLevel} copies it. The published copy of the map shares the old object. When the batch ends, the sets
 * of copied and touched sections are added to the storage's own sets.
 */
final class PropagationJob {
    final boolean sky;
    final int count;
    final Propagator[] workers;
    final AtomicInteger[] outstanding;
    /** Highest phase finished (0 = decreases, 1 = increases). 2 after a failure. */
    volatile int done = -1;
    private volatile Throwable failure;
    private final AtomicInteger helpersRunning = new AtomicInteger();
    /** Sections assigned to a worker by their queued entries ({@link #assign}). Read-only once the workers start. */
    private final Long2IntOpenHashMap assigned;
    private final Thread coordinator = Thread.currentThread();

    final Long2ObjectOpenHashMap<DataLayer> map;
    final LongSet sharedChanged;
    final LightChunkGetter chunkSource;
    final BlockGetter level;
    private final LayerLightSectionStorageAccessor storage;
    private final SkyLightSectionStorageAccessor skyStorage;
    private final DataLayerStorageMap<?> updating;

    /**
     * State that the jobs of one light thread reuse from one batch to the next: the workers with their queues, sets and
     * batch buffers, the section maps of {@link #assign}, and the drained entries. Reuse is safe because a light thread runs
     * one job at a time, and its helpers are done with the job's workers before the job returns. Allocating this state per
     * batch caused most of the garbage of a split batch.
     */
    private static final class Pool {
        Propagator[] workers = new Propagator[0];
        final Long2IntOpenHashMap assigned = new Long2IntOpenHashMap();
        final Long2IntOpenHashMap counts = new Long2IntOpenHashMap();
        long[] decreases = new long[0];
        long[] increases = new long[0];
        long[] sections = new long[0];
        long[] order = new long[0];

        Pool() {
            this.assigned.defaultReturnValue(-1);
        }

        /** The buffers of a huge batch go. A light thread would otherwise keep that batch's size until it times out. */
        void trim() {
            if (this.decreases.length > TRIM_LONGS) {
                this.decreases = new long[0];
            }
            if (this.increases.length > TRIM_LONGS) {
                this.increases = new long[0];
            }
            if (this.sections.length > TRIM_LONGS) {
                this.sections = new long[0];
                this.order = new long[0];
            }
        }
    }

    /** Larger buffers than this, 512 KB, are not kept for the next batch. */
    static final int TRIM_LONGS = 1 << 16;
    private static final ThreadLocal<Pool> POOLS = ThreadLocal.withInitial(Pool::new);

    private final Pool pool;

    private PropagationJob(LightEngineAccessor engine, boolean sky, int count, Pool pool) {
        this.sky = sky;
        this.count = count;
        this.pool = pool;
        LayerLightSectionStorage<?> storage = engine.ga2pl$storage();
        this.storage = (LayerLightSectionStorageAccessor) storage;
        this.skyStorage = sky ? (SkyLightSectionStorageAccessor) storage : null;
        this.updating = this.storage.ga2pl$updatingSectionData();
        this.map = ((DataLayerStorageMapAccessor) this.updating).ga2pl$map();
        this.sharedChanged = this.storage.ga2pl$changedSections();
        this.chunkSource = engine.ga2pl$chunkSource();
        this.level = this.chunkSource.getLevel();
        this.assigned = pool.assigned;
        this.assigned.clear();
        if (pool.workers.length < count) {
            Propagator[] grown = java.util.Arrays.copyOf(pool.workers, count);
            for (int i = pool.workers.length; i < count; i++) {
                grown[i] = new Propagator();
            }
            pool.workers = grown;
        }
        this.workers = new Propagator[count];
        this.outstanding = new AtomicInteger[] { new AtomicInteger(count), new AtomicInteger(count) };
        for (int i = 0; i < count; i++) {
            this.workers[i] = pool.workers[i];
            this.workers[i].reset(this, i, count);
        }
    }

    /**
     * Runs the engine's queued decreases and increases on this thread and up to {@code helpers} helpers. Returns -1 when
     * no helper is free. The caller then runs vanilla's propagation.
     */
    static int run(LightEngineAccessor engine, boolean sky, int helpers) {
        LightWorkerPool.Helper[] reserved = LightWorkerPool.get(helpers).reserve(helpers);
        if (reserved.length == 0) {
            return -1;
        }
        long t0 = System.nanoTime();
        Pool pool = POOLS.get();
        PropagationJob job = new PropagationJob(engine, sky, reserved.length + 1, pool);
        int decreaseCount = drain(engine.ga2pl$decreaseQueue(), pool, true);
        int increaseCount = drain(engine.ga2pl$increaseQueue(), pool, false);
        long[] decreases = pool.decreases;
        long[] increases = pool.increases;
        job.assign(decreases, decreaseCount, increases, increaseCount);
        int entries = job.distribute(decreases, decreaseCount, 0) + job.distribute(increases, increaseCount, 1);
        job.helpersRunning.set(reserved.length);
        for (int i = 0; i < reserved.length; i++) {
            reserved[i].start(job.workers[i + 1]);
        }
        long t1 = System.nanoTime();
        job.workers[0].work();
        long t2 = System.nanoTime();
        job.awaitHelpers();
        long t3 = System.nanoTime();
        ParallelLightStats.timing(t1 - t0, t2 - t1, t3 - t2);
        long processed = 0;
        long sent = 0;
        long most = 0;
        LongSet affected = job.storage.ga2pl$sectionsAffectedByLightUpdates();
        for (Propagator w : job.workers) {
            job.sharedChanged.addAll(w.copied);
            affected.addAll(w.affected);
            processed += w.processed;
            sent += w.sent;
            most = Math.max(most, w.processed);
        }
        // The pooled workers keep their buffers, but not the chunks and sections of this batch.
        for (Propagator w : job.workers) {
            w.release();
        }
        if (job.assigned.size() > 1 << 14) {
            job.assigned.clear();
            job.assigned.trim(1 << 12);
        }
        pool.trim();
        ParallelLightStats.balance(most, processed, job.count);
        if (ParallelLightStats.TRACK != null) {
            StringBuilder sb = new StringBuilder("workers:");
            it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap all = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
            for (Propagator w : job.workers) {
                sb.append(' ').append(w.processed);
                if (w.perSection != null) {
                    for (var e : w.perSection.long2IntEntrySet()) {
                        all.addTo(e.getLongKey(), e.getIntValue());
                    }
                }
            }
            int[] top = all.values().toIntArray();
            java.util.Arrays.sort(top);
            sb.append(" sections ").append(top.length).append(" top:");
            for (int i = top.length - 1; i >= Math.max(0, top.length - 8); i--) {
                sb.append(' ').append(top[i]);
            }
            ParallelLightStats.TRACK.accept(sb.toString());
        }
        // Workers replaced section data in the map. The map's own lookup cache may still hold the old objects.
        job.updating.clearCache();
        Throwable failure = job.failure;
        if (failure != null) {
            // vanilla's batch would have thrown from the same rule on the light thread
            if (failure instanceof RuntimeException e) {
                throw e;
            }
            if (failure instanceof Error e) {
                throw e;
            }
            throw new RuntimeException(failure);
        }
        ParallelLightStats.parallel(job.count, entries, processed, sent, System.nanoTime() - t0);
        return (int) Math.min(Integer.MAX_VALUE, processed);
    }

    /**
     * Moves the entries of the engine queue (position, entry pairs) in queue order into the decrease or increase buffer of the
     * pool. The queue is left empty. Returns the number of longs.
     */
    private static int drain(LongArrayFIFOQueue queue, Pool pool, boolean decreases) {
        int n = queue.size();
        long[] out = decreases ? pool.decreases : pool.increases;
        if (out.length < n) {
            out = new long[Math.max(n, out.length + (out.length >> 1))];
            if (decreases) {
                pool.decreases = out;
            } else {
                pool.increases = out;
            }
        }
        for (int i = 0; i < n; i++) {
            out[i] = queue.dequeueLong();
        }
        return n;
    }

    /**
     * Assigns the sections that hold queued entries to workers by entry count. The fullest section goes first, and each
     * section goes to the worker with the fewest entries so far. Most of the work of a batch sits in a few sections, e.g.
     * the sky light under a new chunk's surface. With a hash alone, the busiest thread got nearly twice an even share.
     * Other sections are assigned by a hash.
     */
    private void assign(long[] decreases, int decreaseCount, long[] increases, int increaseCount) {
        Long2IntOpenHashMap counts = this.pool.counts;
        counts.clear();
        for (int i = 0; i < decreaseCount; i += 2) {
            counts.addTo(SectionPos.blockToSection(decreases[i]), 1);
        }
        for (int i = 0; i < increaseCount; i += 2) {
            counts.addTo(SectionPos.blockToSection(increases[i]), 1);
        }
        int size = counts.size();
        if (this.pool.sections.length < size) {
            this.pool.sections = new long[Math.max(size, this.pool.sections.length * 2)];
            this.pool.order = new long[this.pool.sections.length];
        }
        long[] sections = this.pool.sections;
        long[] order = this.pool.order;
        int n = 0;
        for (Long2IntMap.Entry e : Long2IntMaps.fastIterable(counts)) {
            sections[n] = e.getLongKey();
            // fullest first: sort ascending on (MAX - count, index)
            order[n] = (long) (Integer.MAX_VALUE - e.getIntValue()) << 32 | n;
            n++;
        }
        java.util.Arrays.sort(order, 0, n);
        long[] load = new long[this.count];
        for (int k = 0; k < n; k++) {
            long packed = order[k];
            int least = 0;
            for (int w = 1; w < this.count; w++) {
                if (load[w] < load[least]) {
                    least = w;
                }
            }
            load[least] += Integer.MAX_VALUE - (int) (packed >>> 32);
            this.assigned.put(sections[(int) packed], least);
        }
        if (size > 1 << 14) {
            // The table of a huge batch is not kept for the small batches after it.
            counts.clear();
            counts.trim(1 << 12);
        }
    }

    /** Moves each entry to the queue of its section's owner for phase {@code phase}. */
    private int distribute(long[] entries, int length, int phase) {
        for (int i = 0; i < length; i += 2) {
            LongRing target = this.workers[this.owner(entries[i])].queues[phase];
            target.enqueue(entries[i]);
            target.enqueue(entries[i + 1]);
        }
        return length / 2;
    }

    /**
     * The worker that owns the section of a block. Ownership is per section rather than per chunk, because a batch lights a
     * few chunks and most of a chunk's work sits in a few of its sections. Fixed for the whole batch.
     */
    int owner(long pos) {
        return this.ownerOfSection(BlockPos.getX(pos) >> 4, BlockPos.getY(pos) >> 4, BlockPos.getZ(pos) >> 4);
    }

    int ownerOfSection(int sx, int sy, int sz) {
        int owner = this.assigned.get(SectionPos.asLong(sx, sy, sz));
        if (owner >= 0) {
            return owner;
        }
        int h = sx * 0x9E3779B1 ^ sy * 0xC2B2AE35 ^ sz * 0x85EBCA6B;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        return (h & Integer.MAX_VALUE) % this.count;
    }

    boolean hasLightDataAtOrBelow(int sectionY) {
        return this.skyStorage.ga2pl$hasLightDataAtOrBelow(sectionY);
    }

    boolean lightOnInSection(long section) {
        return this.storage.ga2pl$lightOnInSection(section);
    }

    void finishPhase(int phase) {
        this.done = phase;
        for (Propagator w : this.workers) {
            w.wakeAlways();
        }
    }

    boolean failed() {
        return this.failure != null;
    }

    void fail(Throwable t) {
        synchronized (this) {
            if (this.failure == null) {
                this.failure = t;
            }
        }
        this.done = 2;
        for (Propagator w : this.workers) {
            w.wakeAlways();
        }
    }

    void helperDone() {
        if (this.helpersRunning.decrementAndGet() == 0) {
            // A timed park can oversleep by a millisecond or more (Windows). The last helper therefore wakes the light thread.
            LockSupport.unpark(this.coordinator);
        }
    }

    private void awaitHelpers() {
        int spins = 0;
        while (this.helpersRunning.get() > 0) {
            if (++spins < 2000) {
                Thread.onSpinWait();
            } else {
                LockSupport.parkNanos(this, 1_000_000L);
            }
        }
    }
}
