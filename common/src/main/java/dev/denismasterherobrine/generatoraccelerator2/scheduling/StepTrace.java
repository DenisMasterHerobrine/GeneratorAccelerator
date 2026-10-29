package dev.denismasterherobrine.generatoraccelerator2.scheduling;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * {@code -Dgeneratoraccelerator2.stepTrace=true}: where generated chunks spend their time. Per step, it sums how long chunks waited
 * before the step and how long the step took. The wait counts from the end of the chunk's previous step and covers neighbours'
 * progress and scheduling. The step time counts from the step's call to its result, waits inside the step included. Sums run
 * between {@link #reset} and {@link #report}. The bench calls both per pass.
 */
public final class StepTrace {
    public static final boolean ENABLED = Boolean.getBoolean("generatoraccelerator2.stepTrace");
    private static final int N = 16;
    private static final LongAdder[] GAP = adders(), RUN = adders(), COUNT = adders(), GAPPED = adders();
    /** Per chunk (scrambled key): when its last step ended, and when its first step began. */
    private static final ConcurrentHashMap<Long, long[]> CHUNKS = new ConcurrentHashMap<>();
    private static final LongAdder LIFE = new LongAdder(), LIVES = new LongAdder();

    private StepTrace() {}

    private static LongAdder[] adders() {
        LongAdder[] a = new LongAdder[N];
        for (int i = 0; i < N; i++) {
            a[i] = new LongAdder();
        }
        return a;
    }

    private static long key(ChunkPos pos) {
        return pos.pack() * 0x9E3779B97F4A7C15L;
    }

    /** A step is called for the chunk. Returns the start time. Callers only call it when {@link #ENABLED}. */
    public static long begin(ChunkStatus status, ChunkPos pos) {
        long now = System.nanoTime();
        long[] c = CHUNKS.computeIfAbsent(key(pos), k -> new long[] { 0L, now });
        long last = c[0];
        int i = Math.min(status.getIndex(), N - 1);
        if (last != 0L) {
            GAP[i].add(now - last);
            GAPPED[i].increment();
        }
        return now;
    }

    /** The step's result is in. */
    public static void end(ChunkStatus status, ChunkPos pos, long start) {
        long now = System.nanoTime();
        int i = Math.min(status.getIndex(), N - 1);
        RUN[i].add(now - start);
        COUNT[i].increment();
        long[] c = CHUNKS.get(key(pos));
        if (c != null) {
            c[0] = now;
            if (status == ChunkStatus.FULL) {
                LIFE.add(now - c[1]);
                LIVES.increment();
                CHUNKS.remove(key(pos));
            }
        }
    }

    public static void reset() {
        for (int i = 0; i < N; i++) {
            GAP[i].reset();
            RUN[i].reset();
            COUNT[i].reset();
            GAPPED[i].reset();
        }
        LIFE.reset();
        LIVES.reset();
    }

    /** One line per step: average wait before it and average time in it, in ms. */
    public static String report() {
        StringBuilder b = new StringBuilder("step trace (avg ms waited before / spent in):");
        for (ChunkStatus s : ChunkStatus.getStatusList()) {
            int i = Math.min(s.getIndex(), N - 1);
            long n = COUNT[i].sum(), g = GAPPED[i].sum();
            if (n == 0) {
                continue;
            }
            b.append(String.format(Locale.ROOT, " %s %.1f/%.1f", s.getName(), g == 0 ? 0.0 : GAP[i].sum() / 1e6 / g, RUN[i].sum() / 1e6 / n));
        }
        long lives = LIVES.sum();
        b.append(String.format(Locale.ROOT, "; first step to full avg %.0f ms over %d chunks", lives == 0 ? 0.0 : LIFE.sum() / 1e6 / lives, lives));
        return b.toString();
    }
}
