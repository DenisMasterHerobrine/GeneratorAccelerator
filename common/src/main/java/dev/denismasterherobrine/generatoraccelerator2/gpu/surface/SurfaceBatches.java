package dev.denismasterherobrine.generatoraccelerator2.gpu.surface;

import dev.denismasterherobrine.generatoraccelerator2.gpu.cl.ClApi;
import dev.denismasterherobrine.generatoraccelerator2.gpu.codegen.ClEmitter;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.Lat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.world.level.ChunkPos;

/**
 * The GPU's side of the surface step. All of it runs on the GPU thread, from {@code GpuWorldgen.pump}. Chunks' requests are grouped
 * into batches, each using one slot of buffers. A batch unpacks the noise step's sections that the CPU sends back. It walks every
 * column through the rules ({@code k_sbuild}). It packs every changed section with vanilla's palette as the writes leave it
 * ({@code k_spack}). Finally, it finds the two worldgen heightmaps.
 */
public final class SurfaceBatches {
    /**
     * A chunk's noise-step sections ({@code storage} null for air), WORLD_SURFACE_WG first-available per column (x * 16 + z), and
     * biome window.
     */
    public record Request(ChunkPos pos, long[][] storage, int[] meta, int[] tops, short[] window, int[] cpar, CompletableFuture<Result> future, long since) {}

    /**
     * A chunk's surface. Each section (null if unchanged) has its palette as GPU ids, the count of each entry, the storage bits and
     * words, and the fluid marks of the surface's writes (64 words of 64 bits, indexed by {@code y << 8 | z << 4 | x}). The heightmaps
     * are per column (x * 16 + z): WORLD_SURFACE_WG, then OCEAN_FLOOR_WG, both first-available. {@code cpu} means the chunk needs the
     * CPU's surface, for example for an extension's biome or a palette past 256 entries.
     */
    public record Result(boolean cpu, int[][] palettes, int[][] counts, int[] bits, long[][] storage, long[][] marks, int[] heights) {}

    public static final LongAdder CHUNKS = new LongAdder();
    public static final LongAdder BATCHES = new LongAdder();
    public static final LongAdder TO_CPU = new LongAdder();
    public static final Lat BATCH_LAT = new Lat();
    /** {@code -Dgeneratoraccelerator2.gpu.profile=true} records device time per surface kernel, in nanoseconds (unpack, build, pack, heights). */
    static final boolean PROFILE = Boolean.getBoolean("generatoraccelerator2.gpu.profile");
    public static final String[] KERNELS = { "sunpack", "sbuild", "spack", "sheights" };
    public static final LongAdder[] DEVICE_NANOS = { new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder() };
    private static final int CAP = 32;
    /** An all-air section's storage (never written). */
    private static final long[] ZERO_SECTION = new long[256];
    /**
     * {@code -Dgeneratoraccelerator2.gpu.surfaceSlots=N}: the maximum number of batches in flight (default 4). A SURFACE step waits for
     * its chunk's batch. So time a request spends queued behind full slots adds to every chunk's path.
     */
    private static final int MAX_SLOTS = Math.max(1, Integer.getInteger("generatoraccelerator2.gpu.surfaceSlots", 4));

    private final ClApi cl;
    private final long context, queue;
    private final SurfaceSpec spec;
    private final int height, nsec;
    private final long kUnpack, kBuild, kPack, kHeights;
    private final long perm, od, oi, of, table;
    private final Runnable wake;
    private final ConcurrentLinkedQueue<Request> incoming = new ConcurrentLinkedQueue<>();
    private final ArrayDeque<Request> pending = new ArrayDeque<>();
    private final ArrayDeque<Batch> inflight = new ArrayDeque<>();
    private final ArrayDeque<Slot> free = new ArrayDeque<>();
    private final List<Slot> all = new ArrayList<>();

    public SurfaceBatches(ClApi cl, long context, long queue, long hostQueue, long program, SurfaceSpec spec, int height, long perm, long od, long oi, long of,
                          Runnable wake) {
        this.cl = cl;
        this.context = context;
        this.queue = queue;
        this.hostQueue = hostQueue;
        this.spec = spec;
        this.height = height;
        this.nsec = height / 16;
        this.kUnpack = cl.createKernel(program, "k_sunpack");
        this.kBuild = cl.createKernel(program, "k_sbuild");
        this.kPack = cl.createKernel(program, "k_spack");
        this.kHeights = cl.createKernel(program, "k_sheights");
        this.perm = perm;
        this.od = od;
        this.oi = oi;
        this.of = of;
        ByteBuffer t = ByteBuffer.allocateDirect(Math.max(16, spec.table.length * 4)).order(ByteOrder.nativeOrder());
        t.asIntBuffer().put(spec.table);
        this.table = cl.createBuffer(context, ClApi.MEM_READ_ONLY, t);
        this.wake = wake;
    }

    private final long hostQueue;

    /** Callable from any thread. Returns the chunk's surface, computed on the GPU thread. */
    public CompletableFuture<Result> submit(ChunkPos pos, long[][] storage, int[] meta, int[] tops, short[] window, int[] cpar) {
        CompletableFuture<Result> f = new CompletableFuture<>();
        incoming.add(new Request(pos, storage, meta, tops, window, cpar, f, System.nanoTime()));
        wake.run();
        return f;
    }

    private final class Slot {
        final long inStore, inMeta, blk, blk0, tops, win, cpar, flag, sstore, smeta, smarks, stops;
        final ByteBuffer hStore, hMeta, hTops, hWin, hCpar, hFlag, hSstore, hSmeta, hSmarks, hStops;
        final List<long[]> pins = new ArrayList<>();
        final List<ByteBuffer> pinned = new ArrayList<>();

        Slot() {
            long rw = ClApi.MEM_READ_WRITE;
            long blocks = (long) CAP * 256 * height;
            inStore = cl.createBuffer(context, ClApi.MEM_READ_ONLY, (long) CAP * nsec * 256 * 8);
            inMeta = cl.createBuffer(context, ClApi.MEM_READ_ONLY, (long) CAP * nsec * ClEmitter.META * 4);
            blk = cl.createBuffer(context, rw, blocks * 2);
            blk0 = cl.createBuffer(context, rw, blocks * 2);
            tops = cl.createBuffer(context, ClApi.MEM_READ_ONLY, (long) CAP * 256 * 4);
            win = cl.createBuffer(context, ClApi.MEM_READ_ONLY, (long) CAP * spec.windowInts * 2);
            cpar = cl.createBuffer(context, ClApi.MEM_READ_ONLY, (long) CAP * 8 * 4);
            flag = cl.createBuffer(context, rw, (long) CAP * 4);
            sstore = cl.createBuffer(context, ClApi.MEM_WRITE_ONLY, (long) CAP * nsec * 512 * 8);
            smeta = cl.createBuffer(context, ClApi.MEM_WRITE_ONLY, (long) CAP * nsec * SurfaceSpec.SECTION_META * 4);
            smarks = cl.createBuffer(context, ClApi.MEM_WRITE_ONLY, (long) CAP * nsec * 64 * 8);
            stops = cl.createBuffer(context, ClApi.MEM_WRITE_ONLY, (long) CAP * 512 * 4);
            hStore = pin((long) CAP * nsec * 256 * 8);
            hMeta = pin((long) CAP * nsec * ClEmitter.META * 4);
            hTops = pin((long) CAP * 256 * 4);
            hWin = pin((long) CAP * spec.windowInts * 2);
            hCpar = pin((long) CAP * 8 * 4);
            hFlag = pin((long) CAP * 4);
            hSstore = pin((long) CAP * nsec * 512 * 8);
            hSmeta = pin((long) CAP * nsec * SurfaceSpec.SECTION_META * 4);
            hSmarks = pin((long) CAP * nsec * 64 * 8);
            hStops = pin((long) CAP * 512 * 4);
        }

        ByteBuffer pin(long bytes) {
            long[] m = new long[1];
            ByteBuffer b = cl.pinned(context, hostQueue, bytes, m);
            pins.add(m);
            pinned.add(b);
            return b;
        }

        void release() {
            for (long m : new long[] { inStore, inMeta, blk, blk0, tops, win, cpar, flag, sstore, smeta, smarks, stops }) {
                cl.releaseMem(m);
            }
            for (int i = 0; i < pins.size(); i++) {
                cl.unpin(hostQueue, pins.get(i)[0], pinned.get(i));
            }
        }
    }

    /** {@code kernels}: the batch's kernel launches while profiling, else null. */
    private record Batch(Slot slot, List<Request> reqs, long event, long launched, long[] kernels) {}

    /** GPU thread. Collects finished batches and launches new ones. Returns how long the thread may sleep (as {@code GpuDevice.Pump}). */
    public long pump(long now) {
        Request r;
        while ((r = incoming.poll()) != null) {
            pending.add(r);
        }
        while (!inflight.isEmpty()) {
            Batch b = inflight.peek();
            int st = cl.eventStatus(b.event);
            if (st < 0) {
                throw new IllegalStateException("surface batch failed: OpenCL status " + st);
            }
            if (st != ClApi.CL_COMPLETE) {
                break;
            }
            inflight.poll();
            finish(b);
        }
        long wait = Long.MAX_VALUE;
        boolean launched = false;
        while (!pending.isEmpty()) {
            long age = now - pending.peek().since;
            if (pending.size() < CAP && age < 1_000_000L && !inflight.isEmpty()) {
                wait = Math.min(wait, 1_000_000L - age);
                break;
            }
            Slot s = free.poll();
            if (s == null) {
                if (all.size() >= MAX_SLOTS) {
                    break;
                }
                s = new Slot();
                all.add(s);
            }
            List<Request> take = new ArrayList<>();
            while (!pending.isEmpty() && take.size() < CAP) {
                take.add(pending.poll());
            }
            launch(s, take);
            launched = true;
        }
        if (launched) {
            cl.flush(queue);
        }
        waiting = pending.size();
        return wait;
    }

    /** Published by the GPU thread: the requests waiting for a batch slot. */
    private volatile int waiting;
    /** The tests' slower GPU ({@code GpuDevice.debugSpin}): called with a batch's chunks before its work, or null. */
    public java.util.function.IntConsumer spin;

    /** Whether a full batch already waits for a slot, so a request made now would wait for a whole batch more. Any thread. */
    public boolean behind() {
        return waiting >= CAP;
    }

    private void launch(Slot s, List<Request> reqs) {
        int n = reqs.size();
        s.hStore.clear();
        s.hMeta.clear();
        s.hTops.clear();
        s.hWin.clear();
        s.hCpar.clear();
        s.hFlag.clear();
        // Write the sections in bulk through a view. The buffer's own position is updated below.
        java.nio.LongBuffer store = s.hStore.asLongBuffer();
        for (Request q : reqs) {
            for (int sec = 0; sec < nsec; sec++) {
                long[] w = q.storage()[sec];
                store.put(w != null ? w : ZERO_SECTION);
            }
            for (int v : q.meta()) {
                s.hMeta.putInt(v);
            }
            for (int v : q.tops()) {
                s.hTops.putInt(v);
            }
            for (short v : q.window()) {
                s.hWin.putShort(v);
            }
            int[] cp = q.cpar();
            for (int v : cp) {
                s.hCpar.putInt(v);
            }
            s.hFlag.putInt(0);
        }
        s.hStore.position(store.position() << 3);
        if (spin != null) {
            spin.accept(n);
        }
        rel(cl.write(queue, s.inStore, false, 0, s.hStore.flip()));
        rel(cl.write(queue, s.inMeta, false, 0, s.hMeta.flip()));
        rel(cl.write(queue, s.tops, false, 0, s.hTops.flip()));
        rel(cl.write(queue, s.win, false, 0, s.hWin.flip()));
        rel(cl.write(queue, s.cpar, false, 0, s.hCpar.flip()));
        rel(cl.write(queue, s.flag, false, 0, s.hFlag.flip()));
        int a = 0;
        cl.argMem(kUnpack, a++, s.inStore);
        cl.argMem(kUnpack, a++, s.inMeta);
        cl.argInt(kUnpack, a++, n);
        cl.argMem(kUnpack, a++, s.blk);
        cl.argMem(kUnpack, a, s.blk0);
        long[] kernels = PROFILE ? new long[4] : null;
        kernel(kernels, 0, cl.launch(queue, kUnpack, (long) n * 256 * height, 128));
        a = 0;
        cl.argMem(kBuild, a++, perm);
        cl.argMem(kBuild, a++, od);
        cl.argMem(kBuild, a++, oi);
        cl.argMem(kBuild, a++, of);
        cl.argMem(kBuild, a++, of); // flat: unused by the surface, whose noises read no flat cache.
        cl.argInt(kBuild, a++, 0);
        cl.argInt(kBuild, a++, 0);
        cl.argInt(kBuild, a++, 1);
        cl.argInt(kBuild, a++, 1);
        cl.argMem(kBuild, a++, s.blk);
        cl.argMem(kBuild, a++, s.tops);
        cl.argMem(kBuild, a++, s.win);
        cl.argMem(kBuild, a++, s.cpar);
        cl.argMem(kBuild, a++, table);
        cl.argMem(kBuild, a++, s.flag);
        cl.argInt(kBuild, a, n);
        kernel(kernels, 1, cl.launch(queue, kBuild, (long) n * 256, 64));
        a = 0;
        cl.argMem(kPack, a++, s.blk0);
        cl.argMem(kPack, a++, s.blk);
        cl.argMem(kPack, a++, s.inMeta);
        cl.argInt(kPack, a++, n);
        cl.argMem(kPack, a++, s.sstore);
        cl.argMem(kPack, a++, s.smeta);
        cl.argMem(kPack, a, s.smarks);
        // a work-group of 256 per section
        kernel(kernels, 2, cl.launch(queue, kPack, (long) n * nsec * 256, 256));
        cl.argMem(kHeights, 0, s.blk);
        cl.argInt(kHeights, 1, n);
        cl.argMem(kHeights, 2, s.stops);
        kernel(kernels, 3, cl.launch(queue, kHeights, (long) n * 256, 64));
        rel(cl.read(queue, s.flag, false, 0, s.hFlag.clear().limit(n * 4)));
        rel(cl.read(queue, s.sstore, false, 0, s.hSstore.clear().limit(n * nsec * 512 * 8)));
        rel(cl.read(queue, s.smeta, false, 0, s.hSmeta.clear().limit(n * nsec * SurfaceSpec.SECTION_META * 4)));
        rel(cl.read(queue, s.smarks, false, 0, s.hSmarks.clear().limit(n * nsec * 64 * 8)));
        long ev = cl.read(queue, s.stops, false, 0, s.hStops.clear().limit(n * 512 * 4));
        cl.wakeOnComplete(ev);
        inflight.add(new Batch(s, reqs, ev, System.nanoTime(), kernels));
        BATCHES.increment();
    }

    private void rel(long event) {
        cl.releaseEvent(event);
    }

    /** A kernel launch's event. Kept for its device time while profiling, else released. */
    private void kernel(long[] kernels, int i, long event) {
        if (kernels != null) {
            kernels[i] = event;
        } else {
            rel(event);
        }
    }

    private void finish(Batch b) {
        BATCH_LAT.since(b.launched);
        cl.releaseEvent(b.event);
        if (b.kernels != null) {
            // The queue is in order. If the batch's last read completed, its kernels completed too.
            for (int i = 0; i < b.kernels.length; i++) {
                DEVICE_NANOS[i].add(cl.eventNanos(b.kernels[i]));
                rel(b.kernels[i]);
            }
        }
        Slot s = b.slot;
        var fl = s.hFlag.clear().asIntBuffer();
        var st = s.hSstore.clear().asLongBuffer();
        var me = s.hSmeta.clear().asIntBuffer();
        var mk = s.hSmarks.clear().asLongBuffer();
        var tp = s.hStops.clear().asIntBuffer();
        int sm = SurfaceSpec.SECTION_META;
        List<Result> results = new ArrayList<>(b.reqs.size());
        for (int i = 0; i < b.reqs.size(); i++) {
            boolean cpu = fl.get(i) != 0;
            int[][] pal = new int[nsec][];
            int[][] cnt = new int[nsec][];
            int[] bits = new int[nsec];
            long[][] store = new long[nsec][];
            long[][] marks = new long[nsec][];
            for (int sec = 0; sec < nsec && !cpu; sec++) {
                int base = (i * nsec + sec) * sm;
                int size = me.get(base);
                if (size == 0) {
                    continue;
                }
                if (size < 0) {
                    cpu = true;
                    break;
                }
                int bt = me.get(base + 1);
                bits[sec] = bt;
                pal[sec] = new int[size];
                cnt[sec] = new int[size];
                for (int p = 0; p < size; p++) {
                    pal[sec][p] = me.get(base + 2 + p);
                    cnt[sec][p] = me.get(base + 2 + 256 + p);
                }
                int vpl = 64 / bt;
                int words = (4096 + vpl - 1) / vpl;
                store[sec] = new long[words];
                st.get((i * nsec + sec) * 512, store[sec]);
                long[] m = new long[64];
                mk.get((i * nsec + sec) * 64, m);
                boolean any = false;
                for (long w : m) {
                    any |= w != 0;
                }
                marks[sec] = any ? m : null;
            }
            int[] heights = new int[512];
            tp.get(i * 512, heights);
            if (cpu) {
                TO_CPU.increment();
            }
            results.add(new Result(cpu, pal, cnt, bits, store, marks, heights));
        }
        free.add(s);
        for (int i = 0; i < b.reqs.size(); i++) {
            CHUNKS.increment();
            b.reqs.get(i).future.complete(results.get(i));
        }
    }

    /** Fails every request queued or in flight. Used when the GPU path is off. */
    public void failAll(Throwable e) {
        Request r;
        while ((r = incoming.poll()) != null) {
            r.future.completeExceptionally(e);
        }
        for (Request q : pending) {
            q.future.completeExceptionally(e);
        }
        pending.clear();
        for (Batch b : inflight) {
            for (Request q : b.reqs) {
                q.future.completeExceptionally(e);
            }
        }
        inflight.clear();
    }

    /** GPU thread. Releases the slots no batch uses, returning an idle dimension's memory to the device. New batches recreate them. */
    public void trim() {
        Slot s;
        while ((s = free.poll()) != null) {
            s.release();
            all.remove(s);
        }
    }

    /** GPU thread: releases every buffer and kernel. */
    public void close() {
        failAll(new IllegalStateException("GPU generation closed"));
        for (Slot s : all) {
            s.release();
        }
        all.clear();
        free.clear();
        for (long k : new long[] { kUnpack, kBuild, kPack, kHeights }) {
            cl.releaseKernel(k);
        }
        cl.releaseMem(table);
    }
}
