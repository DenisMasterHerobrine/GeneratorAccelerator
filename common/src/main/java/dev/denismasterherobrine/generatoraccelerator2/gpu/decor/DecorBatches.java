package dev.denismasterherobrine.generatoraccelerator2.gpu.decor;

import dev.denismasterherobrine.generatoraccelerator2.gpu.cl.ClApi;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.Lat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * Runs {@link DecorJob}s on the GPU thread in batches of up to {@link #CAP}, with one batch in flight per decoration queue. A batch
 * gathers the 3x3 chunks of each job from the {@link Resident} pool into its own scratch copy, on the resident queue. The GPU's writes
 * stay in that scratch copy. The pool's copies of the chunks change only through what the decoration writes. The batch then runs
 * {@code k_decor} with one work group per job and reads the results back.
 */
final class DecorBatches {
    static final int CAP = 16;
    static final LongAdder BATCHES = new LongAdder();
    static final LongAdder NO_SLOT = new LongAdder();
    static final Lat BATCH_LAT = new Lat();
    /** Device time of k_decor per batch ({@code -Dgeneratoraccelerator2.gpu.profile=true}). */
    static final Lat KERNEL = new Lat();
    static final LongAdder JOBS = new LongAdder();
    /** GPU thread time per part of a batch: the writes sent before its gather, filling its buffers, enqueueing, reading results, completing jobs. */
    static final LongAdder T_UPLOAD = new LongAdder(), T_FILL = new LongAdder(), T_ENQ = new LongAdder(), T_RESULTS = new LongAdder(), T_COMPLETE = new LongAdder();

    static String timeStatus() {
        return String.format(java.util.Locale.ROOT, "batch parts on the GPU thread: uploads %.0f ms, filling %.0f ms, enqueueing %.0f ms, results %.0f ms, completions %.0f ms; "
            + "predicate tables extended %,d times", T_UPLOAD.sum() / 1e6, T_FILL.sum() / 1e6, T_ENQ.sum() / 1e6, T_RESULTS.sum() / 1e6, T_COMPLETE.sum() / 1e6, EXTENDS.sum());
    }
    /** Per phase of the ore placement (built with -DOD_PROF): nanoseconds and counts summed over jobs. */
    static final java.util.concurrent.atomic.AtomicLongArray PHASES = new java.util.concurrent.atomic.AtomicLongArray(16);
    static final LongAdder PROFILED = new LongAdder();
    private static final boolean PROFILE = Boolean.getBoolean("generatoraccelerator2.gpu.profile");

    private final ClApi cl;
    private final long context, queue, hostQueue;
    private final Resident resident;
    private final DecorSpec spec;
    private final long program;
    private final long fspec, fsin, aux, pb, vt, msin, bbits, sinFix, cosFix, fsinFix, fcosFix, surfaceTable;
    private final List<Slot> free = new ArrayList<>();
    private final ArrayDeque<Batch> inflight = new ArrayDeque<>();
    private final ArrayDeque<DecorJob> waiting = new ArrayDeque<>();
    /** Sends the pending chunk writes (GPU thread). A batch gathers its copies after these writes. */
    Runnable beforeGather;

    private record Batch(Slot slot, List<DecorJob> jobs, long event, long kernel, long launched) {}

    DecorBatches(ClApi cl, long context, long queue, long[] decorQueues, long hostQueue, long program, Resident resident, DecorSpec spec, SinCos sc,
                 SinCos fancy, long surfaceTable) {
        this.cl = cl;
        this.context = context;
        this.queue = queue;
        this.hostQueue = hostQueue;
        this.resident = resident;
        this.spec = spec;
        this.surfaceTable = surfaceTable;
        this.program = program;
        long ro = ClApi.MEM_READ_ONLY;
        this.fspec = cl.createBuffer(context, ro, ints(spec.fspec));
        this.fsin = cl.createBuffer(context, ro, floats(spec.fsin));
        this.aux = cl.createBuffer(context, ro, ints(spec.aux));
        this.pb = cl.createBuffer(context, ro, (long) spec.preds.size() * 2 * Preds.STATE_WORDS * 4);
        this.vt = cl.createBuffer(context, ro, (long) spec.preds.valueTables() * Preds.TABLE * 4);
        // Mth's sine table, as its static initializer fills it
        float[] sin = new float[65536];
        for (int i = 0; i < sin.length; i++) {
            sin[i] = (float) Math.sin((double) i * Math.PI * 2.0 / 65536.0);
        }
        this.msin = cl.createBuffer(context, ro, floats(sin));
        this.bbits = cl.createBuffer(context, ro, ints(spec.biomeBits));
        this.sinFix = cl.createBuffer(context, ro, ints(sc.sinFix));
        this.cosFix = cl.createBuffer(context, ro, ints(sc.cosFix));
        this.fsinFix = cl.createBuffer(context, ro, ints(fancy.sinFix));
        this.fcosFix = cl.createBuffer(context, ro, ints(fancy.cosFix));
        // one batch in flight per queue. Batches run side by side, since a batch fills only as many multiprocessors as it has jobs.
        for (long q : decorQueues) {
            Slot s = new Slot(q);
            s.bind();
            free.add(s);
        }
    }

    private final class Slot {
        final long scratch, hw, hf, jhead, jfeat, gslots, first, cand, dec, dl, fres, wr, rd, prof, tr, ov;
        final ByteBuffer hHw, hJhead, hJfeat, hGslots, hFres, hWr, hRd, hProf, hOv;
        final List<long[]> pins = new ArrayList<>();
        final List<ByteBuffer> pinned = new ArrayList<>();
        final long queue;
        /** This slot's kernels. Their buffer arguments are set once ({@link #bind}). A launch sets only the counts. */
        long kDecor, kGather, kOverlay;

        Slot(long queue) {
            this.queue = queue;
            long rw = ClApi.MEM_READ_WRITE;
            scratch = cl.createBuffer(context, rw, (long) CAP * 9 * resident.slotShorts * 2);
            hw = cl.createBuffer(context, ClApi.MEM_READ_ONLY, (long) CAP * 9 * 256 * 2 * 4);
            hf = cl.createBuffer(context, rw, (long) CAP * 9 * 256 * 4 * 4);
            jhead = cl.createBuffer(context, ClApi.MEM_READ_ONLY, (long) CAP * 4 * 4);
            jfeat = cl.createBuffer(context, ClApi.MEM_READ_ONLY, (long) CAP * DecorSpec.MAXF * 8 * 4);
            gslots = cl.createBuffer(context, ClApi.MEM_READ_ONLY, (long) CAP * 9 * 4);
            first = cl.createBuffer(context, rw, (long) CAP * DecorSpec.L2 * 4);
            cand = cl.createBuffer(context, rw, (long) CAP * DecorSpec.CAND * 4);
            dec = cl.createBuffer(context, rw, (long) CAP * DecorSpec.CAND * 4);
            dl = cl.createBuffer(context, rw, (long) CAP * DecorSpec.CAND * 4);
            fres = cl.createBuffer(context, ClApi.MEM_WRITE_ONLY, (long) CAP * DecorSpec.MAXF * 8 * 4);
            wr = cl.createBuffer(context, ClApi.MEM_WRITE_ONLY, (long) CAP * DecorSpec.MAXW * 2 * 4);
            rd = cl.createBuffer(context, ClApi.MEM_WRITE_ONLY, (long) CAP * DecorSpec.MAXR * 2 * 4);
            prof = cl.createBuffer(context, ClApi.MEM_WRITE_ONLY, (long) CAP * 16 * 8);
            ov = cl.createBuffer(context, ClApi.MEM_READ_ONLY, (long) CAP * DecorJob.MAXO * 2 * 4);
            // the trees' cells start unstamped
            tr = cl.createBuffer(context, rw, ByteBuffer.allocateDirect(CAP * TreeKernel.SCRATCH * 4).order(ByteOrder.nativeOrder()));
            hProf = pin((long) CAP * 16 * 8);
            hHw = pin((long) CAP * 9 * 256 * 2 * 4);
            hJhead = pin((long) CAP * 4 * 4);
            hJfeat = pin((long) CAP * DecorSpec.MAXF * 8 * 4);
            hGslots = pin((long) CAP * 9 * 4);
            hFres = pin((long) CAP * DecorSpec.MAXF * 8 * 4);
            hWr = pin((long) CAP * DecorSpec.MAXW * 2 * 4);
            hRd = pin((long) CAP * DecorSpec.MAXR * 2 * 4);
            hOv = pin((long) CAP * DecorJob.MAXO * 2 * 4);
        }

        /** Creates the slot's kernels and sets every argument that does not change between its launches. */
        void bind() {
            kGather = cl.createKernel(program, "k_dgather");
            cl.argMem(kGather, 0, resident.pool());
            cl.argMem(kGather, 1, gslots);
            cl.argMem(kGather, 3, scratch);
            kOverlay = cl.createKernel(program, "k_doverlay");
            cl.argMem(kOverlay, 0, scratch);
            cl.argMem(kOverlay, 1, ov);
            kDecor = cl.createKernel(program, "k_decor");
            int a = 0;
            cl.argMem(kDecor, a++, scratch);
            cl.argMem(kDecor, a++, hw);
            cl.argMem(kDecor, a++, hf);
            cl.argMem(kDecor, a++, jhead);
            cl.argMem(kDecor, a++, jfeat);
            cl.argMem(kDecor, a++, fspec);
            cl.argMem(kDecor, a++, fsin);
            cl.argMem(kDecor, a++, aux);
            cl.argMem(kDecor, a++, pb);
            cl.argMem(kDecor, a++, bbits);
            cl.argInt(kDecor, a++, spec.biomeWords);
            cl.argMem(kDecor, a++, resident.windows());
            cl.argMem(kDecor, a++, surfaceTable);
            cl.argMem(kDecor, a++, sinFix);
            cl.argMem(kDecor, a++, cosFix);
            cl.argMem(kDecor, a++, first);
            cl.argMem(kDecor, a++, cand);
            cl.argMem(kDecor, a++, dec);
            cl.argMem(kDecor, a++, dl);
            cl.argMem(kDecor, a++, fres);
            cl.argMem(kDecor, a++, wr);
            cl.argMem(kDecor, a++, rd);
            a++; // ARG_STATES: per launch
            cl.argMem(kDecor, a++, prof);
            cl.argMem(kDecor, a++, tr);
            cl.argMem(kDecor, a++, msin);
            cl.argMem(kDecor, a++, vt);
            cl.argMem(kDecor, a++, fsinFix);
            cl.argMem(kDecor, a, fcosFix);
        }

        ByteBuffer pin(long bytes) {
            long[] m = new long[1];
            ByteBuffer b = cl.pinned(context, hostQueue, bytes, m);
            pins.add(m);
            pinned.add(b);
            return b;
        }

        void release() {
            for (long k : new long[] { kDecor, kGather, kOverlay }) {
                if (k != 0) {
                    cl.releaseKernel(k);
                }
            }
            for (long m : new long[] { scratch, hw, hf, jhead, jfeat, gslots, first, cand, dec, dl, fres, wr, rd, prof, tr, ov }) {
                cl.releaseMem(m);
            }
            for (int i = 0; i < pins.size(); i++) {
                cl.unpin(hostQueue, pins.get(i)[0], pinned.get(i));
            }
        }
    }

    /** Queues a job for a batch. The pool holds the job's chunks now, and deltas sent before the job are already enqueued. */
    void add(DecorJob job) {
        waiting.add(job);
    }

    /** Predicate table writes in flight, as (event, buffers). The buffers are kept until the write has read them. */
    private final ArrayDeque<Object[]> tables = new ArrayDeque<>();
    private long lastExtend = Long.MIN_VALUE / 2;
    /** Number of states the device's tables answer for. Kernels are given this count. */
    private int uploadedStates;
    private final java.util.concurrent.atomic.AtomicBoolean extending = new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.ExecutorService EXTENDER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GA2 GPU decoration tables");
        t.setDaemon(true);
        return t;
    });
    /** Minimum interval between extensions of the tables with newly met states. Each write uploads the whole table. */
    private static final long EXTEND_NS = 500_000_000L;
    static final LongAdder EXTENDS = new LongAdder();

    /** Collects finished batches and launches what waits. */
    void pump() {
        while (!tables.isEmpty() && cl.eventStatus((Long) tables.peek()[0]) == ClApi.CL_COMPLETE) {
            cl.releaseEvent((Long) tables.poll()[0]);
        }
        for (var it = inflight.iterator(); it.hasNext(); ) {
            Batch b = it.next();
            int st = cl.eventStatus(b.event);
            if (st < 0) {
                throw new IllegalStateException("decoration batch failed: OpenCL status " + st);
            }
            if (st == ClApi.CL_COMPLETE) {
                it.remove();
                finish(b);
            }
        }
        while (!waiting.isEmpty() && !free.isEmpty()) {
            launch();
        }
    }

    private void launch() {
        Slot s = free.remove(free.size() - 1);
        List<DecorJob> jobs = new ArrayList<>();
        int[] gs = new int[CAP * 9];
        int[] wins = new int[CAP];
        while (!waiting.isEmpty() && jobs.size() < CAP) {
            DecorJob job = waiting.poll();
            boolean ok = true;
            int base = jobs.size() * 9;
            for (int ci = 0; ci < 9 && ok; ci++) {
                int slot = resident.slot(job.chunks[ci].getPos());
                ok = slot >= 0;
                gs[base + ci] = slot;
            }
            if (!ok) {
                NO_SLOT.increment();
                job.future.complete(null);
                continue;
            }
            wins[jobs.size()] = gs[base + 4];
            jobs.add(job);
        }
        if (jobs.isEmpty()) {
            free.add(s);
            return;
        }
        long tf0 = System.nanoTime();
        int n = jobs.size();
        // states met since the last table extension. Their answers are computed on another thread. Meanwhile, kernels send a state
        // past the tables to the CPU. Once ready, the answers are written here, on the resident queue before this batch's gather.
        // Every later kernel waits for its gather. Kernels in flight read only the states below the count they were given, which
        // the write leaves unchanged.
        if (uploadedStates == 0 && spec.preds.tableStates == 0) {
            spec.preds.extend();
        } else if (!extending.get() && tf0 - lastExtend > EXTEND_NS && spec.preds.wantsExtend()) {
            lastExtend = tf0;
            extending.set(true);
            EXTENDER.execute(() -> {
                try {
                    spec.preds.extend();
                } finally {
                    extending.set(false);
                }
            });
        }
        int states = spec.preds.tableStates;
        if (states > uploadedStates) {
            EXTENDS.increment();
            ByteBuffer bb = ints(spec.preds.bits), vb = ints(spec.preds.vals);
            long e1 = cl.write(resident.residentQueue, pb, false, 0, bb);
            long e2 = cl.write(resident.residentQueue, vt, false, 0, vb);
            cl.releaseEvent(e1);
            tables.add(new Object[] { e2, bb, vb });
            uploadedStates = states;
        }
        s.hHw.clear();
        s.hJhead.clear();
        s.hJfeat.clear();
        s.hGslots.clear();
        s.hOv.clear();
        int nov = 0;
        for (int i = 0; i < n; i++) {
            DecorJob job = jobs.get(i);
            int[] o = job.overlay;
            if (o != null) {
                // the job index above the position (bulk: a copy, then the index ored in)
                int[] ov = o.clone();
                for (int k = 0; k < ov.length; k += 2) {
                    ov[k] |= i << 24;
                }
                putInts(s.hOv, ov, ov.length);
                nov += o.length / 2;
            }
            putInts(s.hHw, job.heights, job.heights.length);
            s.hJhead.putInt(job.slots.length).putInt(job.pos.x).putInt(job.pos.z).putInt(wins[i]);
            int[] jf = feat;
            java.util.Arrays.fill(jf, 0);
            for (int f = 0; f < job.slots.length; f++) {
                jf[f * 8] = spec.topEntry.getInt(job.slots[f]);
                jf[f * 8 + 1] = (int) job.lo[f];
                jf[f * 8 + 2] = (int) (job.lo[f] >>> 32);
                jf[f * 8 + 3] = (int) job.hi[f];
                jf[f * 8 + 4] = (int) (job.hi[f] >>> 32);
                jf[f * 8 + 5] = job.slots[f];
            }
            putInts(s.hJfeat, jf, jf.length);
            for (int ci = 0; ci < 9; ci++) {
                s.hGslots.putInt(gs[i * 9 + ci]);
            }
        }
        long tf1 = System.nanoTime();
        T_FILL.add(tf1 - tf0);
        rel(cl.write(s.queue, s.hw, false, 0, s.hHw.flip()));
        rel(cl.write(s.queue, s.jhead, false, 0, s.hJhead.flip()));
        rel(cl.write(s.queue, s.jfeat, false, 0, s.hJfeat.flip()));
        // the gather on the resident queue, after every write sent there so far. A copy-in that reuses a slot waits for the gather.
        if (beforeGather != null) {
            long tu = System.nanoTime();
            beforeGather.run();
            T_UPLOAD.add(System.nanoTime() - tu);
            tf1 += System.nanoTime() - tu;
        }
        rel(cl.write(resident.residentQueue, s.gslots, false, 0, s.hGslots.flip()));
        cl.argInt(s.kGather, 2, n * 9);
        long gathered = cl.launch(resident.residentQueue, s.kGather, (long) n * 9 * (resident.slotShorts / 4), 256);
        if (nov > 0) {
            // the jobs' speculation over their copies, after the gather (in order on the resident queue)
            rel(cl.write(resident.residentQueue, s.ov, false, 0, s.hOv.flip()));
            cl.argInt(s.kOverlay, 2, nov);
            rel(gathered);
            gathered = cl.launch(resident.residentQueue, s.kOverlay, nov, 256);
        }
        cl.flush(resident.residentQueue);
        cl.argInt(s.kDecor, ARG_STATES, uploadedStates);
        long kev = cl.launch(s.queue, s.kDecor, (long) n * DecorSpec.WG, DecorSpec.WG, gathered);
        rel(gathered);
        if (!PROFILE) {
            rel(kev);
            kev = 0;
        }
        rel(cl.read(s.queue, s.fres, false, 0, s.hFres.clear().limit(n * DecorSpec.MAXF * 8 * 4)));
        rel(cl.read(s.queue, s.rd, false, 0, s.hRd.clear().limit(n * DecorSpec.MAXR * 2 * 4)));
        if (PROFILE) {
            rel(cl.read(s.queue, s.prof, false, 0, s.hProf.clear().limit(n * 16 * 8)));
        }
        long ev = cl.read(s.queue, s.wr, false, 0, s.hWr.clear().limit(n * DecorSpec.MAXW * 2 * 4));
        cl.flush(s.queue);
        cl.wakeOnComplete(ev);
        inflight.add(new Batch(s, jobs, ev, kev, System.nanoTime()));
        T_ENQ.add(System.nanoTime() - tf1);
        BATCHES.increment();
        JOBS.add(n);
    }

    private void finish(Batch b) {
        BATCH_LAT.since(b.launched);
        cl.releaseEvent(b.event);
        if (b.kernel != 0) {
            KERNEL.add(cl.eventNanos(b.kernel));
            cl.releaseEvent(b.kernel);
        }
        Slot s = b.slot;
        var fr = s.hFres.clear().asIntBuffer();
        var wr = s.hWr.clear().asIntBuffer();
        var rd = s.hRd.clear().asIntBuffer();
        if (PROFILE) {
            var pr = s.hProf.clear().asLongBuffer();
            for (int i = 0; i < b.jobs.size(); i++) {
                for (int k = 0; k < 16; k++) {
                    PHASES.addAndGet(k, pr.get(i * 16 + k));
                }
            }
            PROFILED.add(b.jobs.size());
        }
        long tr0 = System.nanoTime(), tc = 0;
        for (int i = 0; i < b.jobs.size(); i++) {
            DecorJob job = b.jobs.get(i);
            int nf = job.slots.length;
            int[] features = new int[nf * 8];
            fr.get(i * DecorSpec.MAXF * 8, features);
            int nw = nf == 0 ? 0 : features[(nf - 1) * 8 + 6], nr = nf == 0 ? 0 : features[(nf - 1) * 8 + 7];
            int[] effects = new int[nw * 2];
            wr.get(i * DecorSpec.MAXW * 2, effects);
            int[] reads = new int[nr * 2];
            rd.get(i * DecorSpec.MAXR * 2, reads);
            DecorJob.ANSWERED.since(job.asked);
            long t = System.nanoTime();
            job.future.complete(new DecorJob.Result(features, effects, reads));
            tc += System.nanoTime() - t;
        }
        T_COMPLETE.add(tc);
        T_RESULTS.add(System.nanoTime() - tr0 - tc);
        free.add(s);
    }

    /** Fails what is queued or in flight (the device is going away). */
    void close() {
        for (Batch b : inflight) {
            cl.waitFor(b.event);
            cl.releaseEvent(b.event);
            if (b.kernel != 0) {
                cl.releaseEvent(b.kernel);
            }
            for (DecorJob j : b.jobs) {
                j.future.complete(null);
            }
            free.add(b.slot);
        }
        inflight.clear();
        for (DecorJob j : waiting) {
            j.future.complete(null);
        }
        waiting.clear();
        for (Slot s : free) {
            s.release();
        }
        free.clear();
        for (long m : new long[] { fspec, fsin, aux, pb, vt, msin, bbits, sinFix, cosFix, fsinFix, fcosFix }) {
            cl.releaseMem(m);
        }

    }

    /** Index of the k_decor argument that holds the number of states its tables answer for. */
    private static final int ARG_STATES = 22;

    /** One job's feature rows, filled then copied in bulk. */
    private final int[] feat = new int[DecorSpec.MAXF * 8];

    /** Copies {@code n} ints into the buffer at its position, in bulk, and moves the position past them. */
    private static void putInts(ByteBuffer b, int[] v, int n) {
        b.asIntBuffer().put(v, 0, n);
        b.position(b.position() + n * 4);
    }

    private void rel(long event) {
        cl.releaseEvent(event);
    }

    static ByteBuffer ints(int[] v) {
        ByteBuffer b = ByteBuffer.allocateDirect(Math.max(4, v.length * 4)).order(ByteOrder.nativeOrder());
        b.asIntBuffer().put(v);
        return b;
    }

    static ByteBuffer floats(float[] v) {
        ByteBuffer b = ByteBuffer.allocateDirect(Math.max(4, v.length * 4)).order(ByteOrder.nativeOrder());
        b.asFloatBuffer().put(v);
        return b;
    }
}
