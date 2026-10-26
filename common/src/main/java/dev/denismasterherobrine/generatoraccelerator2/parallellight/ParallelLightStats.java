package dev.denismasterherobrine.generatoraccelerator2.parallellight;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/** Counters for the status lines ({@link ParallelLight#statusLines()}). */
public final class ParallelLightStats {
    private ParallelLightStats() {}

    static final LongAdder PARALLEL_BATCHES = new LongAdder();
    static final LongAdder SERIAL_BATCHES = new LongAdder();
    static final LongAdder NO_HELPER = new LongAdder();
    /** Large batches left to the light thread because it was keeping up ({@code minBusy}). */
    static final LongAdder KEEPING_UP = new LongAdder();
    static final LongAdder WORKERS = new LongAdder();
    static final LongAdder QUEUED = new LongAdder();
    static final LongAdder PROCESSED = new LongAdder();
    static final LongAdder SENT = new LongAdder();
    static final LongAdder NANOS = new LongAdder();
    /** Diagnostics: receives a line per split batch (per-worker entries, the largest sections). */
    public static volatile Consumer<String> TRACK;
    static final LongAdder START_NANOS = new LongAdder();
    static final LongAdder WORK_NANOS = new LongAdder();
    static final LongAdder WAIT_NANOS = new LongAdder();
    /** Sum over split batches of (most entries one thread took) / (entries per thread if even), times 1000. */
    static final LongAdder IMBALANCE = new LongAdder();

    /** Wall time of every light update batch (split or not, or vanilla's when this is off). */
    static final LongAdder BATCH_NANOS = new LongAdder();
    static final LongAdder BATCHES = new LongAdder();

    public static void batch(long nanos) {
        BATCH_NANOS.add(nanos);
        BATCHES.increment();
    }

    /**
     * Whole light updates, their tasks, and the idle time of the light threads between one update and the next. A whole
     * update is {@code ThreadedLevelLightEngine.runUpdate}: the tasks before, the propagation, and the tasks after.
     */
    static final LongAdder RUNS = new LongAdder(), RUN_NANOS = new LongAdder(), RUN_TASKS = new LongAdder(), RUN_GAP_NANOS = new LongAdder();
    static final AtomicLong RUN_MAX_NANOS = new AtomicLong();

    public static void run(long nanos, int tasks, long gapNanos, long cpuNanos) {
        RUN_CPU_NANOS.add(cpuNanos);
        RUNS.increment();
        RUN_NANOS.add(nanos);
        RUN_TASKS.add(tasks);
        RUN_GAP_NANOS.add(gapNanos);
        RUN_MAX_NANOS.accumulateAndGet(nanos, Math::max);
    }

    /** Sums so far: updates, their nanoseconds, their tasks, the nanoseconds between updates. */
    public static long[] runs() {
        return new long[] { RUNS.sum(), RUN_NANOS.sum(), RUN_TASKS.sum(), RUN_GAP_NANOS.sum() };
    }

    /** Parts of a batch on the light thread, per light layer (block 0, sky 1). */
    public static final int CHECKS = 0, DECREASES = 1, INCREASES = 2, OFFLOAD = 3, SPLIT = 4, END = 5;
    private static final String[] PARTS = { "node checks", "decreases", "increases", "increases elsewhere (GPU)", "split", "end of batch" };
    private static final LongAdder[] PART_NANOS = new LongAdder[PARTS.length * 2];
    /** Per light layer: batches and the entries queued when their propagation began. */
    private static final LongAdder[] LAYER_BATCHES = { new LongAdder(), new LongAdder() }, LAYER_QUEUED = { new LongAdder(), new LongAdder() };
    /** CPU time of the light threads in whole updates, where the JVM can tell. */
    static final LongAdder RUN_CPU_NANOS = new LongAdder();

    static {
        for (int i = 0; i < PART_NANOS.length; i++) {
            PART_NANOS[i] = new LongAdder();
        }
    }

    static void part(int layer, int part, long nanos) {
        PART_NANOS[layer * PARTS.length + part].add(nanos);
    }

    static void queued(int layer, int entries) {
        LAYER_BATCHES[layer].increment();
        LAYER_QUEUED[layer].add(entries);
    }

    /** Sums so far, for {@link #partsReport}. */
    public static long[] parts() {
        long[] s = new long[PART_NANOS.length + 5];
        for (int i = 0; i < PART_NANOS.length; i++) {
            s[i] = PART_NANOS[i].sum();
        }
        int n = PART_NANOS.length;
        s[n] = LAYER_BATCHES[0].sum();
        s[n + 1] = LAYER_QUEUED[0].sum();
        s[n + 2] = LAYER_BATCHES[1].sum();
        s[n + 3] = LAYER_QUEUED[1].sum();
        s[n + 4] = RUN_CPU_NANOS.sum();
        return s;
    }

    /**
     * One line: per light layer, the batches and entries queued per chunk and the light thread's ms per chunk in each part of a
     * batch, then the light thread's CPU ms per chunk in whole updates.
     */
    public static String partsReport(long[] before, long chunks) {
        long[] now = parts();
        double c = Math.max(1L, chunks);
        int n = PART_NANOS.length;
        StringBuilder b = new StringBuilder("light batches per chunk:");
        for (int layer = 1; layer >= 0; layer--) {
            b.append(String.format(Locale.ROOT, " %s %.2f batches, %.0f entries queued;", layer == 1 ? "sky" : "block",
                (now[n + layer * 2] - before[n + layer * 2]) / c, (now[n + 1 + layer * 2] - before[n + 1 + layer * 2]) / c));
            for (int p = 0; p < PARTS.length; p++) {
                long d = now[layer * PARTS.length + p] - before[layer * PARTS.length + p];
                if (d > 0) {
                    b.append(String.format(Locale.ROOT, " %s %.3f ms,", PARTS[p], d / 1e6 / c));
                }
            }
            b.setLength(b.length() - 1);
            b.append(';');
        }
        b.append(String.format(Locale.ROOT, " light thread CPU in whole updates %.3f ms", (now[n + 4] - before[n + 4]) / 1e6 / c));
        return b.toString();
    }

    /** Batches on the light thread alone: their queued entries and the entries processed. */
    static final LongAdder SERIAL_QUEUED = new LongAdder(), SERIAL_PROCESSED = new LongAdder();

    /**
     * Sums so far, for the light trace, in this order: split batches, batches on the light thread alone, queued entries of
     * split batches, entries processed in split batches, light thread wait for helpers (ns), queued entries of batches
     * on the light thread alone, and processed entries of those batches.
     */
    public static long[] propagationSnapshot() {
        return new long[] { PARALLEL_BATCHES.sum(), SERIAL_BATCHES.sum(), QUEUED.sum(), PROCESSED.sum(), WAIT_NANOS.sum(), SERIAL_QUEUED.sum(),
            SERIAL_PROCESSED.sum() };
    }

    /** The longest update since the last call. */
    public static long takeRunMax() {
        return RUN_MAX_NANOS.getAndSet(0L);
    }

    /** Wall time the light threads spent in light update batches so far. */
    public static long batchNanos() {
        return BATCH_NANOS.sum();
    }

    static void timing(long start, long work, long wait) {
        START_NANOS.add(start);
        WORK_NANOS.add(work);
        WAIT_NANOS.add(wait);
    }

    static void balance(long most, long total, int workers) {
        if (total > 0) {
            IMBALANCE.add(most * 1000L * workers / total);
        }
    }

    static void parallel(int workers, long queued, long processed, long sent, long nanos) {
        PARALLEL_BATCHES.increment();
        WORKERS.add(workers);
        QUEUED.add(queued);
        PROCESSED.add(processed);
        SENT.add(sent);
        NANOS.add(nanos);
    }

    /** Clears the counters (benchmarks compare configurations). */
    public static void reset() {
        for (LongAdder a : new LongAdder[] { PARALLEL_BATCHES, SERIAL_BATCHES, NO_HELPER, KEEPING_UP, WORKERS, QUEUED, PROCESSED, SENT, NANOS, START_NANOS, WORK_NANOS, WAIT_NANOS, IMBALANCE, BATCH_NANOS, BATCHES,
            RUNS, RUN_NANOS, RUN_TASKS, RUN_GAP_NANOS, SERIAL_QUEUED, SERIAL_PROCESSED, RUN_CPU_NANOS }) {
            a.reset();
        }
        for (LongAdder a : PART_NANOS) {
            a.reset();
        }
        for (int layer = 0; layer < 2; layer++) {
            LAYER_BATCHES[layer].reset();
            LAYER_QUEUED[layer].reset();
        }
        RUN_MAX_NANOS.set(0L);
    }

    static List<String> lines() {
        List<String> out = new ArrayList<>();
        long batches = PARALLEL_BATCHES.sum();
        out.add(String.format(Locale.ROOT, "parallel light: %d batches split (%.1f threads on average), %d on the light thread alone (%d large ones while it kept up, %d with no helper free)",
            batches, batches == 0 ? 0.0 : WORKERS.sum() / (double) batches, SERIAL_BATCHES.sum(), KEEPING_UP.sum(), NO_HELPER.sum()));
        long processed = PROCESSED.sum();
        out.add(String.format(Locale.ROOT, "  split batches: %d queued entries, %d processed, %.1f%% of them handed to another thread, %.1f ms",
            QUEUED.sum(), processed, processed == 0 ? 0.0 : 100.0 * SENT.sum() / processed, NANOS.sum() / 1e6));
        out.add(String.format(Locale.ROOT, "  all light update batches: %d, %.1f ms on the light threads", BATCHES.sum(), BATCH_NANOS.sum() / 1e6));
        long runs = RUNS.sum();
        out.add(String.format(Locale.ROOT, "  whole light updates (tasks, propagation, tasks): %d, %.2f ms and %.1f tasks each on average, %.2f ms idle between them",
            runs, runs == 0 ? 0.0 : RUN_NANOS.sum() / 1e6 / runs, runs == 0 ? 0.0 : RUN_TASKS.sum() / (double) runs, runs == 0 ? 0.0 : RUN_GAP_NANOS.sum() / 1e6 / runs));
        out.add(String.format(Locale.ROOT, "  split batches on the light thread: %.1f ms handing out, %.1f ms propagating, %.1f ms waiting for helpers; busiest thread took %.2fx an even share",
            START_NANOS.sum() / 1e6, WORK_NANOS.sum() / 1e6, WAIT_NANOS.sum() / 1e6, batches == 0 ? 0.0 : IMBALANCE.sum() / 1000.0 / batches));
        return out;
    }
}
