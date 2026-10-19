package dev.denismasterherobrine.generatoraccelerator2.parallellight;

import dev.denismasterherobrine.generatoraccelerator2.platform.PlatformHooks;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * One thread's share of a parallel propagation ({@link PropagationJob}). It processes the queue entries of the sections it
 * owns with vanilla's per-direction rules of {@code SkyLightEngine} and {@code BlockLightEngine} ({@code propagateIncrease},
 * {@code propagateDecrease}, {@code propagateFromEmptySections}, {@code countEmptySectionsBelowIfAtBorder}).
 *
 * <p>A worker reads and writes the light of its own sections only. A step into another worker's section is sent to that worker.
 * The message is the same queue entry, narrowed to that one direction and marked {@link #REMOTE}. The owner runs vanilla's rule
 * for that direction: the comparison with the stored level, the opacity and shape checks, the write, and the queued entries.
 * Every read a rule makes is a read that vanilla's rule also makes. Block states and storage presence are read anywhere. Light
 * levels are read only in the owner's sections. The rules are therefore vanilla's. Only the order in which entries are taken
 * differs, and vanilla's propagation reaches the same light in any order.
 */
final class Propagator {
    /** Marks an entry sent by another worker. It has one direction and skips the check of the source's stored level. */
    static final long REMOTE = 1L << 12;
    /** With {@link #REMOTE}: fill the receiver's section column below an empty section ({@code propagateFromEmptySections}). */
    static final long FILL = 1L << 13;
    private static final long LEVEL_MASK = 15L;
    private static final long DIRECTIONS_MASK = 1008L;
    private static final long FROM_EMPTY_SHAPE = 1024L;
    private static final long FROM_EMISSION = 2048L;
    /** Longs of entries in a batch sent to another worker. A batch array holds its count first ({@link #send}). */
    private static final int BATCH = 512;
    /** Spent batch arrays a worker keeps for its own sends. */
    private static final int FREE_BATCHES = 64;

    private static final Direction[] DIRECTIONS = Direction.values();
    private static final int[] OPPOSITE = new int[6];
    private static final int[] DX = new int[6];
    private static final int[] DY = new int[6];
    private static final int[] DZ = new int[6];

    static {
        for (Direction d : DIRECTIONS) {
            OPPOSITE[d.ordinal()] = d.getOpposite().ordinal();
            DX[d.ordinal()] = d.getStepX();
            DY[d.ordinal()] = d.getStepY();
            DZ[d.ordinal()] = d.getStepZ();
        }
    }

    /**
     * The job this worker runs now. Workers are pooled per light thread ({@link PropagationJob}) and {@link #reset} for each job.
     * The queues, sets and batch arrays a job leaves in a worker keep their capacity.
     */
    PropagationJob job;
    int id;
    private boolean sky;
    /** Phase 0: decreases. Phase 1: increases, including the increases that decreases queue for after them. */
    final LongRing[] queues = { new LongRing(), new LongRing() };
    @SuppressWarnings("unchecked")
    private final ConcurrentLinkedQueue<long[]>[] inbox = new ConcurrentLinkedQueue[] { new ConcurrentLinkedQueue<long[]>(), new ConcurrentLinkedQueue<long[]>() };
    private long[][] outbox = new long[0][];
    private int[] outCount = new int[0];
    /** Received and consumed batch arrays, reused for this worker's own sends. Only this worker's thread touches the deque. */
    private final java.util.ArrayDeque<long[]> free = new java.util.ArrayDeque<>();
    private volatile Thread thread;
    private volatile boolean parked;
    private int phase;

    /** Sections this worker copied (made its own) in this batch, and the sections whose light it changed or touched. */
    final LongOpenHashSet copied = new LongOpenHashSet();
    final LongOpenHashSet affected = new LongOpenHashSet();
    long processed;
    /** Entries taken per section. Diagnostics only: allocated only while {@code ParallelLightStats.TRACK} is set. */
    it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap perSection;
    long sent;

    private long lastCopied = Long.MAX_VALUE;
    private final long[] affectedKeys = filled(16);

    private static final int CHUNK_SLOTS = 16;
    private final long[] chunkKeys = filled(CHUNK_SLOTS, ChunkPos.INVALID_CHUNK_POS);
    private final LightChunk[] chunks = new LightChunk[CHUNK_SLOTS];
    private final byte[] kinds = new byte[CHUNK_SLOTS];
    private final LevelChunkSection[][] sections = new LevelChunkSection[CHUNK_SLOTS][];
    private final int[] minY = new int[CHUNK_SLOTS];

    private static final int LAYER_SLOTS = 32;
    private final long[] layerKeys = filled(LAYER_SLOTS);
    private final DataLayer[] layers = new DataLayer[LAYER_SLOTS];

    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos fromPos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos shapePos = new BlockPos.MutableBlockPos();
    private long fromKey = Long.MAX_VALUE;
    private BlockState fromState;

    Propagator() {
    }

    /** Prepares this worker for a job as if new, but keeps its existing queues, sets and batch arrays. */
    void reset(PropagationJob job, int id, int workers) {
        this.job = job;
        this.id = id;
        this.sky = job.sky;
        if (this.outbox.length != workers) {
            this.outbox = new long[workers][];
            this.outCount = new int[workers];
        } else {
            Arrays.fill(this.outbox, null);
            Arrays.fill(this.outCount, 0);
        }
        // Empty after a finished job. A failed job may have left entries or batches.
        for (LongRing q : this.queues) {
            q.clear();
        }
        this.inbox[0].clear();
        this.inbox[1].clear();
        this.thread = null;
        this.parked = false;
        this.phase = 0;
        this.copied.clear();
        this.affected.clear();
        this.processed = 0;
        this.sent = 0;
        if (ParallelLightStats.TRACK != null) {
            if (this.perSection == null) {
                this.perSection = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
            } else {
                this.perSection.clear();
            }
        }
        this.lastCopied = Long.MAX_VALUE;
        Arrays.fill(this.affectedKeys, Long.MAX_VALUE);
        Arrays.fill(this.chunkKeys, ChunkPos.INVALID_CHUNK_POS);
        Arrays.fill(this.layerKeys, Long.MAX_VALUE);
        this.fromKey = Long.MAX_VALUE;
        this.fromState = null;
    }

    /** After a job, releases the job's chunks, sections and light data, and the tables of a huge batch. The pool keeps the worker. */
    void release() {
        this.job = null;
        this.thread = null;
        Arrays.fill(this.chunks, null);
        Arrays.fill(this.sections, null);
        Arrays.fill(this.chunkKeys, ChunkPos.INVALID_CHUNK_POS);
        Arrays.fill(this.layers, null);
        Arrays.fill(this.layerKeys, Long.MAX_VALUE);
        this.fromState = null;
        this.fromKey = Long.MAX_VALUE;
        for (LongRing q : this.queues) {
            q.trim(1 << 20);
        }
        if (this.copied.size() > 1 << 14) {
            this.copied.clear();
            this.copied.trim(1 << 10);
        }
        if (this.affected.size() > 1 << 14) {
            this.affected.clear();
            this.affected.trim(1 << 10);
        }
    }

    /** A batch array for sending. Reuses one this worker consumed, else allocates a new one. */
    private long[] batchArray() {
        long[] b = this.free.pollLast();
        return b != null ? b : new long[BATCH + 1];
    }

    private static long[] filled(int n) {
        return filled(n, Long.MAX_VALUE);
    }

    private static long[] filled(int n, long value) {
        long[] a = new long[n];
        Arrays.fill(a, value);
        return a;
    }

    // ------------------------------------------------------------------------------------------------ work loop

    /** Runs both phases. On a failure, records it and returns. The job then wakes every worker. */
    void work() {
        this.thread = Thread.currentThread();
        try {
            this.runPhase(0);
            this.runPhase(1);
        } catch (Throwable t) {
            this.job.fail(t);
        }
    }

    private void runPhase(int p) {
        this.phase = p;
        while (this.job.done < p - 1) {
            if (this.job.failed()) {
                return;
            }
            this.idleWait(p - 1);
        }
        LongRing queue = this.queues[p];
        ConcurrentLinkedQueue<long[]> in = this.inbox[p];
        boolean active = true;
        while (true) {
            int sinceFlush = 0;
            while (!queue.isEmpty()) {
                long position = queue.dequeueLong();
                long entry = queue.dequeueLong();
                if (p == 0) {
                    this.decrease(position, entry);
                } else {
                    this.increase(position, entry);
                }
                this.processed++;
                if (this.perSection != null && ParallelLightStats.TRACK != null) {
                    this.perSection.addTo(SectionPos.blockToSection(position), 1);
                }
                if (++sinceFlush == 4096) {
                    // keep the other workers supplied during a long run of local work
                    sinceFlush = 0;
                    this.flushAll();
                    if (this.job.failed()) {
                        return;
                    }
                }
            }
            this.flushAll();
            long[] batch = in.poll();
            if (batch != null) {
                this.load(batch, queue);
                if (active) {
                    this.job.outstanding[p].decrementAndGet();
                } else {
                    // This worker takes over the batch's unit in the count.
                    active = true;
                }
                continue;
            }
            if (active) {
                active = false;
                if (this.job.outstanding[p].decrementAndGet() == 0) {
                    // No worker is active and nothing is in flight. The phase is over.
                    this.job.finishPhase(p);
                    return;
                }
            }
            while (true) {
                if (this.job.done >= p || this.job.failed()) {
                    return;
                }
                batch = in.poll();
                if (batch != null) {
                    this.load(batch, queue);
                    active = true;
                    break;
                }
                this.idleWait(p);
            }
        }
    }

    private void load(long[] batch, LongRing queue) {
        int n = (int) batch[0];
        for (int i = 1; i <= n; i += 2) {
            queue.enqueue(batch[i]);
            queue.enqueue(batch[i + 1]);
        }
        if (batch.length == BATCH + 1 && this.free.size() < FREE_BATCHES) {
            this.free.addLast(batch);
        }
    }

    /** Waits a little for a batch or the end of phase {@code p}. */
    private void idleWait(int p) {
        ConcurrentLinkedQueue<long[]> in = p >= 0 ? this.inbox[p] : null;
        for (int spin = 0; spin < 200; spin++) {
            if (this.job.done >= p || this.job.failed() || (in != null && !in.isEmpty())) {
                return;
            }
            Thread.onSpinWait();
        }
        this.parked = true;
        if (this.job.done < p && !this.job.failed() && (in == null || in.isEmpty())) {
            LockSupport.parkNanos(this, 200_000L);
        }
        this.parked = false;
    }

    void wake() {
        Thread t = this.thread;
        if (t != null && this.parked) {
            LockSupport.unpark(t);
        }
    }

    void wakeAlways() {
        Thread t = this.thread;
        if (t != null) {
            LockSupport.unpark(t);
        }
    }

    /** Queues an entry for the inbox of {@code target}. A batch array holds its number of longs at index 0, then the entries. */
    private void send(int target, long position, long entry) {
        long[] buffer = this.outbox[target];
        if (buffer == null) {
            buffer = this.outbox[target] = this.batchArray();
        }
        int n = this.outCount[target];
        buffer[n + 1] = position;
        buffer[n + 2] = entry;
        n += 2;
        this.sent++;
        if (n == BATCH) {
            this.outbox[target] = null;
            this.outCount[target] = 0;
            buffer[0] = n;
            this.publish(target, buffer);
        } else {
            this.outCount[target] = n;
        }
    }

    private void flushAll() {
        for (int target = 0; target < this.outCount.length; target++) {
            int n = this.outCount[target];
            if (n > 0) {
                long[] buffer = this.outbox[target];
                this.outCount[target] = 0;
                if (n >= BATCH / 4) {
                    // A large partial batch is sent as is, with its count in front. The next entries start another array.
                    this.outbox[target] = null;
                    buffer[0] = n;
                    this.publish(target, buffer);
                } else {
                    // A small batch is sent as a copy of its entries. The array stays for the next entries.
                    long[] batch = new long[n + 1];
                    batch[0] = n;
                    System.arraycopy(buffer, 1, batch, 1, n);
                    this.publish(target, batch);
                }
            }
        }
    }

    private void publish(int target, long[] batch) {
        // Counted before the batch is visible, so the count never reaches zero while the batch is in flight.
        this.job.outstanding[this.phase].incrementAndGet();
        Propagator to = this.job.workers[target];
        to.inbox[this.phase].add(batch);
        to.wake();
    }

    /** Whether the step from {@code (x, y, z)} in {@code direction} leaves its section (the unit a worker owns). */
    private static boolean leavesSection(int direction, int x, int y, int z) {
        return switch (direction) {
            case 0 -> (y & 15) == 0;
            case 1 -> (y & 15) == 15;
            case 2 -> (z & 15) == 0;
            case 3 -> (z & 15) == 15;
            case 4 -> (x & 15) == 0;
            case 5 -> (x & 15) == 15;
            default -> false;
        };
    }

    // ------------------------------------------------------------------------------------------------ increases

    /** The loop body of {@code propagateIncreases} for one entry, then {@code propagateIncrease}. */
    private void increase(long position, long entry) {
        if ((entry & FILL) != 0) {
            this.fill(position, entry, true);
            return;
        }
        int level = (int) (entry & LEVEL_MASK);
        this.fromKey = Long.MAX_VALUE;
        if ((entry & REMOTE) != 0) {
            int d = Long.numberOfTrailingZeros(entry & DIRECTIONS_MASK) - 4;
            this.increaseIn(position, entry, level, d, BlockPos.offset(position, DIRECTIONS[d]), this.sky ? this.emptySectionsBelow(position) : 0);
            return;
        }
        int x = BlockPos.getX(position);
        int y = BlockPos.getY(position);
        int z = BlockPos.getZ(position);
        long section = SectionPos.blockToSection(position);
        int stored = this.layer(section).get(x & 15, y & 15, z & 15);
        if ((entry & FROM_EMISSION) != 0 && stored < level) {
            this.set(x, y, z, section, level);
            stored = level;
        }
        if (stored != level) {
            return;
        }
        int emptyBelow = this.sky ? this.emptySectionsBelow(position) : 0;
        for (int d = 0; d < 6; d++) {
            if ((entry & 1L << d + 4) == 0) {
                continue;
            }
            long to = BlockPos.asLong(x + DX[d], y + DY[d], z + DZ[d]);
            if (leavesSection(d, x, y, z)) {
                int owner = this.job.owner(to);
                if (owner != this.id) {
                    this.send(owner, position, entry & (LEVEL_MASK | FROM_EMPTY_SHAPE) | 1L << d + 4 | REMOTE);
                    continue;
                }
            }
            this.increaseIn(position, entry, level, d, to, emptyBelow);
        }
    }

    /** The body of vanilla's {@code propagateIncrease} for one direction. */
    private void increaseIn(long from, long entry, int level, int d, long to, int emptyBelow) {
        long toSection = SectionPos.blockToSection(to);
        DataLayer layer = this.layer(toSection);
        if (layer == null) {
            return;
        }
        int x = BlockPos.getX(to);
        int y = BlockPos.getY(to);
        int z = BlockPos.getZ(to);
        int stored = layer.get(x & 15, y & 15, z & 15);
        if (level - 1 <= stored) {
            return;
        }
        this.pos.set(x, y, z);
        BlockState state = this.state(x, y, z, this.pos);
        int lit = level - Math.max(1, state.getLightBlock(this.job.level, this.pos));
        if (lit <= stored) {
            return;
        }
        BlockState fromBlock = (entry & FROM_EMPTY_SHAPE) != 0 ? Blocks.AIR.defaultBlockState() : this.fromState(from);
        if (this.occludes(from, fromBlock, to, state, d)) {
            return;
        }
        this.set(x, y, z, toSection, lit);
        if (lit > 1) {
            this.queues[1].enqueue(to);
            this.queues[1].enqueue(increaseSkipOneDirection(lit, isEmptyShape(state), OPPOSITE[d]));
        }
        if (this.sky) {
            this.fromEmptySections(x, y, z, d, lit, true, emptyBelow);
        }
    }

    // ------------------------------------------------------------------------------------------------ decreases

    /** Vanilla's {@code propagateDecrease} for one entry. */
    private void decrease(long position, long entry) {
        if ((entry & FILL) != 0) {
            this.fill(position, entry, false);
            return;
        }
        int fromLevel = (int) (entry & LEVEL_MASK);
        if ((entry & REMOTE) != 0) {
            int d = Long.numberOfTrailingZeros(entry & DIRECTIONS_MASK) - 4;
            this.decreaseIn(fromLevel, d, BlockPos.offset(position, DIRECTIONS[d]), this.sky ? this.emptySectionsBelow(position) : 0);
            return;
        }
        int emptyBelow = this.sky ? this.emptySectionsBelow(position) : 0;
        int x = BlockPos.getX(position);
        int y = BlockPos.getY(position);
        int z = BlockPos.getZ(position);
        for (int d = 0; d < 6; d++) {
            if ((entry & 1L << d + 4) == 0) {
                continue;
            }
            long to = BlockPos.asLong(x + DX[d], y + DY[d], z + DZ[d]);
            if (leavesSection(d, x, y, z)) {
                int owner = this.job.owner(to);
                if (owner != this.id) {
                    this.send(owner, position, entry & LEVEL_MASK | 1L << d + 4 | REMOTE);
                    continue;
                }
            }
            this.decreaseIn(fromLevel, d, to, emptyBelow);
        }
    }

    /** The body of vanilla's {@code propagateDecrease} for one direction (sky or block). */
    private void decreaseIn(int fromLevel, int d, long to, int emptyBelow) {
        long toSection = SectionPos.blockToSection(to);
        DataLayer layer = this.layer(toSection);
        if (layer == null) {
            return;
        }
        int x = BlockPos.getX(to);
        int y = BlockPos.getY(to);
        int z = BlockPos.getZ(to);
        int stored = layer.get(x & 15, y & 15, z & 15);
        if (stored == 0) {
            return;
        }
        int back = OPPOSITE[d];
        if (stored <= fromLevel - 1) {
            if (this.sky) {
                this.set(x, y, z, toSection, 0);
                this.queues[0].enqueue(to);
                this.queues[0].enqueue(decreaseSkipOneDirection(stored, back));
                this.fromEmptySections(x, y, z, d, stored, false, emptyBelow);
            } else {
                this.pos.set(x, y, z);
                BlockState state = this.state(x, y, z, this.pos);
                int emission = this.emission(toSection, state);
                this.set(x, y, z, toSection, 0);
                if (emission < stored) {
                    this.queues[0].enqueue(to);
                    this.queues[0].enqueue(decreaseSkipOneDirection(stored, back));
                }
                if (emission > 0) {
                    this.queues[1].enqueue(to);
                    this.queues[1].enqueue(increaseLightFromEmission(emission, isEmptyShape(state)));
                }
            }
        } else {
            this.queues[1].enqueue(to);
            this.queues[1].enqueue(increaseOnlyOneDirection(stored, false, back));
        }
    }

    /** {@code BlockLightEngine.getEmission}: the emission of the block at {@link #pos}, or 0 where the column has light off. */
    private int emission(long section, BlockState state) {
        int emission = PlatformHooks.lightEmission(state, this.job.level, this.pos);
        return emission > 0 && this.job.lightOnInSection(section) ? emission : 0;
    }

    // ------------------------------------------------------------------------------------------------ sky light's empty sections

    /** {@code SkyLightEngine.countEmptySectionsBelowIfAtBorder}. */
    private int emptySectionsBelow(long position) {
        int y = BlockPos.getY(position);
        if ((y & 15) != 0) {
            return 0;
        }
        int x = BlockPos.getX(position);
        int z = BlockPos.getZ(position);
        int lx = x & 15;
        int lz = z & 15;
        if (lx != 0 && lx != 15 && lz != 0 && lz != 15) {
            return 0;
        }
        int sx = x >> 4;
        int sy = y >> 4;
        int sz = z >> 4;
        int n = 0;
        // Presence only. The key set does not change during the batch, so this may read another worker's column.
        while (!this.job.map.containsKey(SectionPos.asLong(sx, sy - n - 1, sz)) && this.job.hasLightDataAtOrBelow(sy - n - 1)) {
            n++;
        }
        return n;
    }

    /** {@code SkyLightEngine.propagateFromEmptySections} at a block of this worker's own column. */
    private void fromEmptySections(int x, int y, int z, int d, int level, boolean increase, int emptyBelow) {
        if (emptyBelow == 0) {
            return;
        }
        boolean crossed = switch (d) {
            case 2 -> (z & 15) == 15;
            case 3 -> (z & 15) == 0;
            case 4 -> (x & 15) == 15;
            case 5 -> (x & 15) == 0;
            default -> false;
        };
        if (!crossed) {
            return;
        }
        int sx = x >> 4;
        int sz = z >> 4;
        int sectionY = (y >> 4) - 1;
        int last = sectionY - emptyBelow + 1;
        while (sectionY >= last) {
            long section = SectionPos.asLong(sx, sectionY, sz);
            // Presence only. The section below may belong to another worker.
            if (!this.job.map.containsKey(section)) {
                sectionY--;
                continue;
            }
            int owner = this.job.ownerOfSection(sx, sectionY, sz);
            if (owner == this.id) {
                this.fillColumn(x, sectionY, z, section, d, level, increase);
            } else {
                // The owner fills the column of its section. The phase tells whether the fill is an increase or a decrease.
                this.send(owner, BlockPos.asLong(x, sectionY << 4, z), (long) level | 1L << d + 4 | REMOTE | FILL);
            }
            sectionY--;
        }
    }

    /** The inner loop of {@code propagateFromEmptySections} for one section: the block column of {@code (x, z)}, top down. */
    private void fillColumn(int x, int sectionY, int z, long section, int d, int level, boolean increase) {
        int baseY = sectionY << 4;
        int back = OPPOSITE[d];
        for (int i = 15; i >= 0; i--) {
            long at = BlockPos.asLong(x, baseY + i, z);
            if (increase) {
                this.set(x, baseY + i, z, section, level);
                if (level > 1) {
                    this.queues[1].enqueue(at);
                    this.queues[1].enqueue(increaseSkipOneDirection(level, true, back));
                }
            } else {
                this.set(x, baseY + i, z, section, 0);
                this.queues[0].enqueue(at);
                this.queues[0].enqueue(decreaseSkipOneDirection(level, back));
            }
        }
    }

    /** A {@link #FILL} message: fill this worker's section column below an empty section. */
    private void fill(long position, long entry, boolean increase) {
        int x = BlockPos.getX(position);
        int z = BlockPos.getZ(position);
        int sectionY = BlockPos.getY(position) >> 4;
        int d = Long.numberOfTrailingZeros(entry & DIRECTIONS_MASK) - 4;
        this.fillColumn(x, sectionY, z, SectionPos.asLong(x >> 4, sectionY, z >> 4), d, (int) (entry & LEVEL_MASK), increase);
    }

    // ------------------------------------------------------------------------------------------------ storage

    /** {@code storage.getDataLayer(section, true)} for a section this worker owns. */
    private DataLayer layer(long section) {
        int slot = (int) (section >>> 42) & 3 | ((int) (section >>> 20) & 3) << 2 | ((int) section & 1) << 4;
        if (this.layerKeys[slot] == section) {
            return this.layers[slot];
        }
        DataLayer layer = this.job.map.get(section);
        if (layer != null) {
            this.layerKeys[slot] = section;
            this.layers[slot] = layer;
        }
        return layer;
    }

    /** {@code storage.setStoredLevel(pos, level)}. Copies the section the first time it is written in the batch. */
    private void set(int x, int y, int z, long section, int level) {
        DataLayer layer;
        if (section != this.lastCopied && !this.copied.contains(section) && !this.job.sharedChanged.contains(section)) {
            layer = this.job.map.get(section).copy();
            this.job.map.put(section, layer);
            this.copied.add(section);
            int slot = (int) (section >>> 42) & 3 | ((int) (section >>> 20) & 3) << 2 | ((int) section & 1) << 4;
            this.layerKeys[slot] = section;
            this.layers[slot] = layer;
        } else {
            layer = this.layer(section);
        }
        this.lastCopied = section;
        layer.set(x & 15, y & 15, z & 15, level);
        if (((x + 1) & 15) > 1 && ((y + 1) & 15) > 1 && ((z + 1) & 15) > 1) {
            this.affect(section);
        } else {
            // SectionPos.aroundAndAtBlockPos: the sections touched by the 3x3x3 neighbourhood of the block
            int x0 = (x - 1) >> 4;
            int x1 = (x + 1) >> 4;
            int y0 = (y - 1) >> 4;
            int y1 = (y + 1) >> 4;
            int z0 = (z - 1) >> 4;
            int z1 = (z + 1) >> 4;
            for (int sx = x0; sx <= x1; sx++) {
                for (int sy = y0; sy <= y1; sy++) {
                    for (int sz = z0; sz <= z1; sz++) {
                        this.affect(SectionPos.asLong(sx, sy, sz));
                    }
                }
            }
        }
    }

    private void affect(long section) {
        int slot = (int) (section ^ section >>> 20 ^ section >>> 42) & 15;
        if (this.affectedKeys[slot] != section) {
            this.affected.add(section);
            this.affectedKeys[slot] = section;
        }
    }

    // ------------------------------------------------------------------------------------------------ blocks

    private static final byte KIND_OTHER = 0;
    private static final byte KIND_PROTO = 1;
    private static final byte KIND_LEVEL = 2;

    private int chunkSlot(int cx, int cz) {
        long key = ChunkPos.asLong(cx, cz);
        int slot = cx & 3 | (cz & 3) << 2;
        if (this.chunkKeys[slot] != key) {
            LightChunk chunk = this.job.chunkSource.getChunkForLighting(cx, cz);
            this.chunkKeys[slot] = key;
            this.chunks[slot] = chunk;
            byte kind = chunk == null ? KIND_OTHER : kindOf(chunk);
            this.kinds[slot] = kind;
            if (kind != KIND_OTHER) {
                if (chunk instanceof ImposterProtoChunk imposter) {
                    this.sections[slot] = imposter.getWrapped().getSections();
                    this.minY[slot] = imposter.getWrapped().getMinBuildHeight();
                } else {
                    this.sections[slot] = ((net.minecraft.world.level.chunk.ChunkAccess) chunk).getSections();
                    this.minY[slot] = chunk.getMinBuildHeight();
                }
            } else {
                this.sections[slot] = null;
            }
        }
        return slot;
    }

    private static byte kindOf(LightChunk chunk) {
        Class<?> c = chunk.getClass();
        if (c == ProtoChunk.class) {
            return KIND_PROTO;
        } else if (c == LevelChunk.class) {
            return ((LevelChunk) chunk).getLevel().isDebug() ? KIND_OTHER : KIND_LEVEL;
        } else if (c == ImposterProtoChunk.class) {
            LevelChunk wrapped = ((ImposterProtoChunk) chunk).getWrapped();
            return wrapped.getClass() == LevelChunk.class && !wrapped.getLevel().isDebug() ? KIND_LEVEL : KIND_OTHER;
        }
        return KIND_OTHER;
    }

    /** {@code LightEngine.getState}. Bedrock where no chunk is lightable, else the block as the chunk's class reads it. */
    private BlockState state(int x, int y, int z, BlockPos.MutableBlockPos at) {
        int slot = this.chunkSlot(x >> 4, z >> 4);
        LightChunk chunk = this.chunks[slot];
        if (chunk == null) {
            return Blocks.BEDROCK.defaultBlockState();
        }
        byte kind = this.kinds[slot];
        if (kind == KIND_OTHER) {
            return chunk.getBlockState(at.set(x, y, z));
        }
        LevelChunkSection[] array = this.sections[slot];
        int min = this.minY[slot];
        int index = (y >> 4) - (min >> 4);
        if (kind == KIND_PROTO) {
            // ProtoChunk.getBlockState: void air outside the build height, and air for an all-air section
            if (y < min || index >= array.length) {
                return Blocks.VOID_AIR.defaultBlockState();
            }
            LevelChunkSection section = array[index];
            return section.hasOnlyAir() ? Blocks.AIR.defaultBlockState() : dev.denismasterherobrine.generatoraccelerator2.chunkio.StateReads.get(section, x & 15, y & 15, z & 15);
        }
        // LevelChunk.getBlockState: air outside the sections
        if (index >= 0 && index < array.length) {
            LevelChunkSection section = array[index];
            if (!section.hasOnlyAir()) {
                return dev.denismasterherobrine.generatoraccelerator2.chunkio.StateReads.get(section, x & 15, y & 15, z & 15);
            }
        }
        return Blocks.AIR.defaultBlockState();
    }

    /** The source block of the entry being processed, read once per entry like vanilla's lazily fetched {@code blockstate}. */
    private BlockState fromState(long from) {
        if (this.fromKey != from) {
            this.fromKey = from;
            this.fromState = this.state(BlockPos.getX(from), BlockPos.getY(from), BlockPos.getZ(from), this.fromPos);
        }
        return this.fromState;
    }

    private static boolean isEmptyShape(BlockState state) {
        return !state.canOcclude() || !state.useShapeForLightOcclusion();
    }

    /** {@code LightEngine.shapeOccludes}. Two blocks without occlusion shapes get vanilla's answer for two empty shapes. */
    private boolean occludes(long from, BlockState fromBlock, long to, BlockState toBlock, int d) {
        boolean fromEmpty = isEmptyShape(fromBlock);
        boolean toEmpty = isEmptyShape(toBlock);
        if (fromEmpty && toEmpty) {
            return false;
        }
        VoxelShape a = fromEmpty ? Shapes.empty() : fromBlock.getFaceOcclusionShape(this.job.level, this.shapePos.set(from), DIRECTIONS[d]);
        VoxelShape b = toEmpty ? Shapes.empty() : toBlock.getFaceOcclusionShape(this.job.level, this.shapePos.set(to), DIRECTIONS[OPPOSITE[d]]);
        return Shapes.faceShapeOccludes(a, b);
    }

    // ------------------------------------------------------------------------------------------------ queue entries (LightEngine.QueueEntry)

    private static long withLevel(long entry, int level) {
        return entry & -16L | (long) level & 15L;
    }

    private static long decreaseSkipOneDirection(int level, int direction) {
        return withLevel(DIRECTIONS_MASK & ~(1L << direction + 4), level);
    }

    private static long increaseLightFromEmission(int level, boolean emptyShape) {
        long entry = DIRECTIONS_MASK | FROM_EMISSION;
        if (emptyShape) {
            entry |= FROM_EMPTY_SHAPE;
        }
        return withLevel(entry, level);
    }

    private static long increaseSkipOneDirection(int level, boolean emptyShape, int direction) {
        long entry = DIRECTIONS_MASK & ~(1L << direction + 4);
        if (emptyShape) {
            entry |= FROM_EMPTY_SHAPE;
        }
        return withLevel(entry, level);
    }

    private static long increaseOnlyOneDirection(int level, boolean emptyShape, int direction) {
        long entry = emptyShape ? FROM_EMPTY_SHAPE : 0L;
        entry |= 1L << direction + 4;
        return withLevel(entry, level);
    }
}
