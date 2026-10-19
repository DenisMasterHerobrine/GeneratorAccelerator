package dev.denismasterherobrine.generatoraccelerator2.gpu.decor;

import dev.denismasterherobrine.generatoraccelerator2.gpu.cl.ClApi;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.world.level.ChunkPos;

/**
 * The GPU's copy of chunk blocks, from each chunk's surface on (GPU thread only). Each chunk has one slot of {@code 256 x height}
 * 16-bit {@link StateIds}, with columns x * 16 + z and y from the bottom (the surface kernels' layout). A chunk's slot is filled from
 * its surface batch. It is kept up to date with the sections later steps wrote, sent whole as they are stored ({@link #upload}). When
 * the pool is full, the least recently used slot is reused. A job that needs a chunk without a slot leaves that work to the CPU.
 */
public final class Resident {
    private final ClApi cl;
    private final long context, queue;
    /** Uploads, gathers and reads ({@code GpuDevice.residentQueue}). The copy-in stays on the main queue after the surface kernels. */
    public final long residentQueue;
    /** Set when a slot was taken from another chunk since the last copy-in. The copy-in then waits for the resident queue's work first. */
    private boolean reused;
    private final int height;
    public final long slotShorts;
    private final long pool;
    /** Each slot's stored quart biomes as the zoom reads them (the surface's window), for the biome filter. */
    private final long windows;
    public final int windowShorts;
    public final int capacity;
    private final long kUnpack, kCopyIn, kBlocks;
    private final LinkedHashMap<Long, Integer> slots = new LinkedHashMap<>(64, 0.75f, true);
    private final ArrayDeque<Integer> free = new ArrayDeque<>();

    /** Staging buffers for uploads: { pinned mem, device mem, write event, capacity }, with their mapped host buffer. */
    private record Stage(long[] m, ByteBuffer host) {}
    private final ArrayDeque<Stage> staged = new ArrayDeque<>();
    private final ArrayDeque<Stage> idle = new ArrayDeque<>();
    private final long hostQueue;

    /** Ints per section in an upload's header: slot, section, bits, palette offset, storage offset (longs), mode (0 palette, 1 ids). */
    public static final int HEAD = 6;

    public Resident(ClApi cl, long context, long queue, long residentQueue, long hostQueue, long program, int height, int windowShorts, long budgetBytes) {
        this.hostQueue = hostQueue;
        this.residentQueue = residentQueue;
        this.cl = cl;
        this.context = context;
        this.queue = queue;
        this.height = height;
        this.slotShorts = 256L * height;
        this.capacity = (int) Math.max(64, Math.min(8192, budgetBytes / (slotShorts * 2)));
        this.pool = cl.createBuffer(context, ClApi.MEM_READ_WRITE, capacity * slotShorts * 2);
        this.windowShorts = windowShorts;
        this.windows = cl.createBuffer(context, ClApi.MEM_READ_WRITE, (long) capacity * windowShorts * 2);
        this.kUnpack = cl.createKernel(program, "k_dunpack");
        this.kCopyIn = cl.createKernel(program, "k_dcopyin");
        this.kBlocks = cl.createKernel(program, "k_dblocks");
        for (int i = 0; i < capacity; i++) {
            free.add(i);
        }
    }

    public long pool() {
        return pool;
    }

    public long windows() {
        return windows;
    }

    /**
     * A chunk's key: its position scrambled (a bijection). {@code Long.hashCode} of {@code x | z << 32} is {@code x ^ z}, which is the
     * same along whole diagonals. That would turn the map's bins into trees.
     */
    static long key(ChunkPos pos) {
        return pos.toLong() * 0x9E3779B97F4A7C15L;
    }

    /** The chunk's slot, or -1. */
    public int slot(ChunkPos pos) {
        Integer s = slots.get(key(pos));
        return s == null ? -1 : s;
    }

    /** A slot for the chunk (its old one, else a free one, else the least recently used chunk's). */
    public int take(ChunkPos pos) {
        long k = key(pos);
        Integer s = slots.get(k);
        if (s != null) {
            return s;
        }
        Integer f = free.poll();
        if (f == null) {
            Iterator<Map.Entry<Long, Integer>> it = slots.entrySet().iterator();
            f = it.next().getValue();
            it.remove();
            EVICTED.increment();
            reused = true;
            if (onEvict != null) {
                onEvict.accept(f);
            }
        }
        slots.put(k, f);
        return f;
    }

    public static final java.util.concurrent.atomic.LongAdder EVICTED = new java.util.concurrent.atomic.LongAdder();
    /** Called when a slot goes to another chunk (GPU thread). Work pending for the old chunk must not land on the slot. */
    public java.util.function.IntConsumer onEvict;

    public void drop(ChunkPos pos) {
        Integer s = slots.remove(key(pos));
        if (s != null) {
            free.add(s);
            // the next copy-in may take the slot. The writes sent to the slot before go first.
            reused = true;
        }
    }

    /**
     * Enqueues the copy of a surface batch's chunks (blocks {@code blk}, biome windows {@code win}, chunk i at i's offsets) into their
     * slots ({@code cpar[i * 8 + 6]}, taken when the batch was launched), in one kernel.
     */
    public void copyBatch(int n, long blk, long win, long cpar) {
        if (reused && residentQueue != queue) {
            // a slot of this batch held another chunk. The resident queue's gathers and uploads of that slot go first.
            long m = cl.barrier(residentQueue, 0L);
            cl.flush(residentQueue);
            cl.releaseEvent(cl.barrier(queue, m));
            cl.releaseEvent(m);
        }
        reused = false;
        cl.argMem(kCopyIn, 0, pool);
        cl.argMem(kCopyIn, 1, windows);
        cl.argMem(kCopyIn, 2, blk);
        cl.argMem(kCopyIn, 3, win);
        cl.argMem(kCopyIn, 4, cpar);
        cl.argInt(kCopyIn, 5, n);
        cl.releaseEvent(cl.launch(queue, kCopyIn, (long) n * (slotShorts / 4 + windowShorts), 256));
    }

    /**
     * Enqueues sections into slots. The input is {@code n} headers of {@link #HEAD} ints, the palettes' state ids (or a section's 4096
     * ids), and the storages' longs as {@code PalettedContainer} keeps them. That is vanilla's bit layout, where a value never spans two
     * longs. Then {@code nb} blocks ({@code slot << 16 | state}, {@code yi << 8 | z << 4 | x}), one per block, are written over the
     * sections.
     */
    public void upload(int n, int[] head, int[] pal, int palLen, long[] raw, int rawLen, int[] blocks, int nb) {
        if (n == 0 && nb == 0) {
            return;
        }
        long headBytes = (long) n * HEAD * 4, palBytes = (long) palLen * 4;
        long rawAt = (headBytes + palBytes + 7) & ~7L;
        long blocksAt = rawAt + (long) rawLen * 8;
        long bytes = blocksAt + (long) nb * 8;
        // buffers whose write has read them go back, oldest first. They are never waited for, since the queue may be busy.
        while (!staged.isEmpty() && cl.eventStatus(staged.peek().m[2]) == ClApi.CL_COMPLETE) {
            Stage done = staged.poll();
            cl.releaseEvent(done.m[2]);
            done.m[2] = 0;
            idle.add(done);
        }
        Stage st = null;
        for (var it = idle.iterator(); it.hasNext(); ) {
            Stage c = it.next();
            if (c.m[3] >= bytes) {
                it.remove();
                st = c;
                break;
            }
        }
        if (st == null && idle.size() >= 4) {
            // every idle buffer is too small: the smallest goes (sizes only grow)
            Stage small = null;
            for (Stage c : idle) {
                if (small == null || c.m[3] < small.m[3]) {
                    small = c;
                }
            }
            idle.remove(small);
            cl.unpin(hostQueue, small.m[0], small.host);
            cl.releaseMem(small.m[1]);
        }
        if (st == null) {
            long cap = Math.max(1L << 16, Long.highestOneBit(Math.max(1, bytes - 1)) << 1);
            long[] m = new long[1];
            ByteBuffer host = cl.pinned(context, hostQueue, cap, m);
            st = new Stage(new long[] { m[0], cl.createBuffer(context, ClApi.MEM_READ_ONLY, cap), 0, cap }, host);
        }
        long[] r = st.m;
        ByteBuffer h = st.host;
        h.clear();
        h.asIntBuffer().put(head, 0, n * HEAD);
        h.position((int) headBytes);
        h.slice().order(h.order()).asIntBuffer().put(pal, 0, palLen);
        h.position((int) rawAt);
        h.slice().order(h.order()).asLongBuffer().put(raw, 0, rawLen);
        h.position((int) blocksAt);
        h.slice().order(h.order()).asIntBuffer().put(blocks, 0, nb * 2);
        h.position(0);
        h.limit((int) bytes);
        r[2] = cl.write(residentQueue, r[1], false, 0, h);
        staged.add(st);
        if (n > 0) {
            cl.argMem(kUnpack, 0, pool);
            cl.argMem(kUnpack, 1, r[1]);
            cl.argInt(kUnpack, 2, (int) headBytes);
            cl.argInt(kUnpack, 3, (int) rawAt);
            cl.argInt(kUnpack, 4, n);
            cl.releaseEvent(cl.launch(residentQueue, kUnpack, (long) n * 4096, 256));
        }
        if (nb > 0) {
            // after the sections (an in-order queue). A block written after its section was sent whole is newer than the section.
            cl.argMem(kBlocks, 0, pool);
            cl.argMem(kBlocks, 1, r[1]);
            cl.argInt(kBlocks, 2, (int) blocksAt);
            cl.argInt(kBlocks, 3, nb);
            cl.releaseEvent(cl.launch(residentQueue, kBlocks, nb, 256));
        }
        cl.flush(residentQueue);
    }

    /** Enqueues a read of one slot (verification). Returns the event. */
    public long read(int slot, ByteBuffer into) {
        long ev = cl.read(residentQueue, pool, false, slot * slotShorts * 2, into);
        cl.flush(residentQueue);
        return ev;
    }

    public void close() {
        for (Stage st : staged) {
            cl.waitFor(st.m[2]);
            cl.releaseEvent(st.m[2]);
            idle.add(st);
        }
        staged.clear();
        for (Stage st : idle) {
            cl.unpin(hostQueue, st.m[0], st.host);
            cl.releaseMem(st.m[1]);
        }
        idle.clear();
        cl.releaseKernel(kCopyIn);
        cl.releaseKernel(kUnpack);
        cl.releaseKernel(kBlocks);
        cl.releaseMem(pool);
        cl.releaseMem(windows);
    }

    /** The copy-in and upload kernels (appended to the program with the surface's). */
    public static String source(int height, int windowShorts) {
        long per = 256L * height / 4;
        return "__kernel void k_dcopyin(__global ulong* pool, __global ushort* wins, __global const ulong* blk, __global const ushort* win, "
            + "__global const int* cpar, int n) {\n"
            + "  long g = get_global_id(0); long per = " + per + "L + " + windowShorts + "L; if (g >= (long) n * per) return;\n"
            + "  int i = (int) (g / per); long r = g % per; long slot = cpar[i * 8 + 6];\n"
            + "  if (r < " + per + "L) pool[slot * " + per + "L + r] = blk[(long) i * " + per + "L + r];\n"
            + "  else { long w = r - " + per + "L; wins[slot * " + windowShorts + "L + w] = win[(long) i * " + windowShorts + "L + w]; }\n}\n"
            + "__kernel void k_dunpack(__global ushort* pool, __global const uchar* buf, int palAt, int rawAt, int n) {\n"
            + "  long g = get_global_id(0); if (g >= (long) n * 4096) return;\n"
            + "  int e = (int) (g >> 12), idx = (int) (g & 4095);\n"
            + "  __global const int* hd = (__global const int*) buf + e * " + HEAD + ";\n"
            + "  __global const int* pal = (__global const int*) (buf + palAt);\n"
            + "  __global const ulong* raw = (__global const ulong*) (buf + rawAt);\n"
            + "  if (hd[0] < 0) return;\n"
            + "  int bits = hd[2], v;\n"
            + "  if (hd[5] == 1) v = pal[hd[3] + idx];\n"
            + "  else if (bits == 0) v = pal[hd[3]];\n"
            + "  else { int vpl = 64 / bits; ulong w = raw[hd[4] + idx / vpl]; int sh = (idx % vpl) * bits; v = pal[hd[3] + (int) ((w >> sh) & ((1UL << bits) - 1UL))]; }\n"
            + "  int y = idx >> 8, z = (idx >> 4) & 15, x = idx & 15;\n"
            + "  pool[(long) hd[0] * " + (256L * height) + "L + (x * 16 + z) * " + height + " + hd[1] * 16 + y] = (ushort) v;\n}\n"
            + "__kernel void k_dblocks(__global ushort* pool, __global const uchar* buf, int at, int n) {\n"
            + "  int g = get_global_id(0); if (g >= n) return;\n"
            + "  __global const int* e = (__global const int*) (buf + at) + g * 2;\n"
            + "  int a = e[0], idx = e[1], x = idx & 15, z = (idx >> 4) & 15, yi = idx >> 8; if (a == -1) return;\n"
            + "  pool[(long) ((uint) a >> 16) * " + (256L * height) + "L + (x * 16 + z) * " + height + " + yi] = (ushort) (a & 0xffff);\n}\n";
    }
}
