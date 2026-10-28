package dev.denismasterherobrine.generatoraccelerator2.light;

import com.mojang.datafixers.util.Pair;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Light tasks handed straight to the light thread ({@code ThreadedLightTasksMixin}), and where the light thread's tasks spend
 * their time.
 *
 * <p>Vanilla queues every light task in the chunk priority sorter. The sorter hands the light thread one chunk's tasks, waits
 * until the light thread has taken them, then hands over the next chunk's. While the light thread runs an update, chunks wait in
 * the sorter. An update starts only from the server thread's polls or once 1000 tasks are waiting. With this on, tasks go into a
 * queue the light thread takes from at the start of each update. An update starts as soon as tasks are queued and the light
 * thread is free. Each update takes at most vanilla's 1000 tasks. Tasks keep their order, so every chunk's tasks run in the order
 * they were queued. The order between chunks follows arrival instead of the chunks' ticket levels. The light that results does
 * not depend on that order ({@code LightOrderTest}).
 *
 * <p>Each task is counted by the engine method that queued it, before or after the update's propagation: how many ran, the
 * light thread's time in them, and how long they waited from being queued to running.
 *
 * <p>{@code -Dgeneratoraccelerator2.directLightTasks=false} turns it off.
 */
public final class LightTasks {
    private LightTasks() {}

    public static final boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.directLightTasks"));

    /** Tasks per update in vanilla's {@code ThreadedLevelLightEngine.runUpdate}. */
    public static final int BATCH = 1000;

    /** Whether a light engine made now takes its tasks directly. */
    public static boolean on() {
        return ENABLED && LightConfig.directTasks() && HookTargets.allowed(HookTargets.LIGHT_TASKS);
    }

    /** The engine methods that queue tasks. */
    public static final int CHECK_BLOCK = 0, SECTION_STATUS = 1, LIGHT_SOURCES = 2, LIGHT_ENABLED = 3, SECTION_DATA = 4, RETAIN_DATA = 5,
        CHUNK_STATUS = 6, INITIALIZE_LIGHT = 7, LIGHT_CHUNK = 8, OTHER = 9;
    private static final String[] KINDS = { "block changes", "section status", "light sources", "light enabled", "section data", "retain data",
        "chunk status", "initialize light", "light chunk", "other" };

    /** The engine method the thread is in, set when the method starts. Its tasks are queued within it. */
    private static final ThreadLocal<int[]> KIND = ThreadLocal.withInitial(() -> new int[] { OTHER });

    /** Per kind and part (before or after the propagation): tasks run, nanoseconds in them, nanoseconds from queued to started. */
    private static final LongAdder[] COUNT = adders(), NANOS = adders(), LATENCY = adders();
    private static final LongAdder QUEUED = new LongAdder(), WAITED = new LongAdder(), WAIT_NANOS = new LongAdder();
    private static final AtomicLong WAIT_MAX = new AtomicLong();

    private static LongAdder[] adders() {
        LongAdder[] a = new LongAdder[KINDS.length * 2];
        for (int i = 0; i < a.length; i++) {
            a[i] = new LongAdder();
        }
        return a;
    }

    public static void kind(int kind) {
        KIND.get()[0] = kind;
    }

    /**
     * The entry for vanilla's task list. The task is wrapped to be timed. {@code type} is vanilla's task type, before (0) or after
     * (1) the propagation.
     */
    public static Pair<Object, Runnable> queued(Object type, Runnable task) {
        QUEUED.increment();
        int slot = KIND.get()[0] * 2 + ((Enum<?>) type).ordinal();
        return Pair.of(type, new Timed(slot, System.nanoTime(), task));
    }

    /** The light thread took the entry into an update. */
    public static void taken(Pair<?, Runnable> entry, long now) {
        if (entry.getSecond() instanceof Timed t) {
            long w = now - t.queued;
            WAITED.increment();
            WAIT_NANOS.add(w);
            WAIT_MAX.accumulateAndGet(w, Math::max);
        }
    }

    private static final class Timed implements Runnable {
        final int slot;
        final long queued;
        final Runnable task;

        Timed(int slot, long queued, Runnable task) {
            this.slot = slot;
            this.queued = queued;
            this.task = task;
        }

        @Override
        public void run() {
            long t0 = System.nanoTime();
            LATENCY[this.slot].add(t0 - this.queued);
            try {
                this.task.run();
            } finally {
                NANOS[this.slot].add(System.nanoTime() - t0);
                COUNT[this.slot].increment();
            }
        }

        @Override
        public String toString() {
            return this.task.toString();
        }
    }

    /** Sums so far, for {@link #report}. */
    public static long[] snapshot() {
        long[] s = new long[4 + COUNT.length * 3];
        s[0] = QUEUED.sum();
        s[1] = WAITED.sum();
        s[2] = WAIT_NANOS.sum();
        s[3] = WAIT_MAX.getAndSet(0L);
        for (int i = 0; i < COUNT.length; i++) {
            s[4 + i * 3] = COUNT[i].sum();
            s[5 + i * 3] = NANOS[i].sum();
            s[6 + i * 3] = LATENCY[i].sum();
        }
        return s;
    }

    /**
     * One line: the tasks queued per chunk, their wait for an update, and per kind and part the tasks per chunk, the light thread's
     * ms per chunk in them, and their average ms from queued to started.
     */
    public static String report(long[] before, long chunks) {
        long[] now = snapshot();
        double c = Math.max(1L, chunks);
        long waited = now[1] - before[1];
        StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "light tasks: %.1f queued per chunk, %.2f ms on average before their update (longest %.1f);"
            + " per chunk (tasks, light thread ms, ms from queued to run):", (now[0] - before[0]) / c, waited == 0 ? 0.0 : (now[2] - before[2]) / 1e6 / waited, now[3] / 1e6));
        for (int i = 0; i < COUNT.length; i++) {
            long n = now[4 + i * 3] - before[4 + i * 3];
            if (n == 0) {
                continue;
            }
            b.append(String.format(Locale.ROOT, " %s %s %.2f, %.3f, %.1f;", KINDS[i / 2], i % 2 == 0 ? "before" : "after", n / c,
                (now[5 + i * 3] - before[5 + i * 3]) / 1e6 / c, (now[6 + i * 3] - before[6 + i * 3]) / 1e6 / n));
        }
        b.setLength(b.length() - 1);
        return b.toString();
    }

    /** Status lines for {@code /ga2 light}, totals since the start. */
    public static List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        lines.add("light tasks: " + (!ENABLED ? "through vanilla's sorter (-Dgeneratoraccelerator2.directLightTasks=false)"
            : !LightConfig.directTasks() ? "through vanilla's sorter (light.toml directTasks)"
            : !HookTargets.allowed(HookTargets.LIGHT_TASKS) ? "through vanilla's sorter (another mod changes how light tasks are queued)"
            : "straight to the light thread") + ", " + QUEUED.sum() + " queued so far");
        long tasks = 0;
        long nanos = 0;
        for (int i = 0; i < COUNT.length; i++) {
            tasks += COUNT[i].sum();
            nanos += NANOS[i].sum();
        }
        long waited = WAITED.sum();
        lines.add(String.format(Locale.ROOT, "  %,d run, %.1f ms on the light thread; %.2f ms on average from queued to their update",
            tasks, nanos / 1e6, waited == 0 ? 0.0 : WAIT_NANOS.sum() / 1e6 / waited));
        return lines;
    }
}
