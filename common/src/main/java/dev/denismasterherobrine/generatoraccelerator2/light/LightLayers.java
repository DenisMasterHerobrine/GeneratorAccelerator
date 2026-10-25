package dev.denismasterherobrine.generatoraccelerator2.light;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.world.level.lighting.LightEngine;
import org.slf4j.Logger;

/**
 * Block light and sky light of a server light update on two threads at once.
 *
 * <p>A light engine holds two independent engines, one per light layer. Each has its own section storage, queues and caches.
 * Vanilla's {@code LevelLightEngine} passes every call to both, block light first. During the tasks before an update's
 * propagation, the light thread runs the sky light engine's part of each call right away and records the block light engine's
 * part. At the propagation, a second thread (a lane, one per dimension) replays the recorded calls in their order and runs the
 * block light propagation and end of batch, while the light thread runs the sky light's. The light thread waits for the lane,
 * then runs the tasks after the propagation as usual. Each layer sees exactly the calls it sees in vanilla, in the same order.
 * The layers share only reads of the chunks and the level, and the "light changed" notifications, which the server thread
 * collects into sets. Light is identical.
 *
 * <p>Off on machines with four processor threads or fewer, where a second light thread only takes a processor from generation.
 * {@code -Dgeneratoraccelerator2.lightLayers=false} turns it off.
 */
public final class LightLayers {
    private LightLayers() {}

    private static final Logger LOGGER = Ga2Log.get("Light");

    /** Not final so benchmarks can compare both ways. Read when a light engine is made. */
    public static boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.lightLayers"));

    /** Fewer recorded block light calls than this, with no block light work queued, run on the light thread. */
    public static final int MIN_CALLS = 4;

    /** Whether a light engine made now runs its layers at once. */
    public static boolean on() {
        return ENABLED && LightConfig.parallelLayers() && Runtime.getRuntime().availableProcessors() > 4
            && HookTargets.allowed(HookTargets.LIGHT_LAYERS);
    }

    static final LongAdder UPDATES = new LongAdder(), INLINE = new LongAdder(), CALLS = new LongAdder(), LANE_NANOS = new LongAdder(),
        WAIT_NANOS = new LongAdder();

    public static void inline() {
        INLINE.increment();
    }

    private static final AtomicInteger LANES = new AtomicInteger();

    /** A thread that runs one dimension's block light: the recorded calls, then the propagation. */
    public static final class Lane implements Runnable {
        private final Thread thread;
        private volatile List<Runnable> calls;
        private volatile LightEngine<?, ?> engine;
        private volatile boolean done = true;
        private int result;
        private Throwable error;
        private long nanos;
        private volatile Thread waiter;

        public Lane() {
            this.thread = new Thread(this, "GA2 Light Lane #" + LANES.incrementAndGet());
            this.thread.setDaemon(true);
            // as the light helpers: at the light thread's level, never above the thread that starts it
            this.thread.setPriority(Math.min(Thread.NORM_PRIORITY + 2, Thread.currentThread().getPriority()));
            this.thread.setUncaughtExceptionHandler((t, e) -> LOGGER.error("uncaught exception on {}", t.getName(), e));
            this.thread.start();
        }

        /** Starts the calls and the propagation of {@code engine} on the lane. The caller must {@link #join} before the next start. */
        public void start(List<Runnable> calls, LightEngine<?, ?> engine) {
            this.error = null;
            this.done = false;
            this.engine = engine;
            this.waiter = Thread.currentThread();
            // written last: the lane takes the job when it sees the calls
            this.calls = calls;
            LockSupport.unpark(this.thread);
        }

        /** Waits for the job. Returns the propagation's result. Rethrows what the job threw. */
        public int join() {
            long t0 = System.nanoTime();
            for (int spins = 0; !this.done; spins++) {
                if (spins < 2000) {
                    Thread.onSpinWait();
                } else {
                    LockSupport.park(this);
                }
            }
            WAIT_NANOS.add(System.nanoTime() - t0);
            UPDATES.increment();
            LANE_NANOS.add(this.nanos);
            if (this.error != null) {
                if (this.error instanceof RuntimeException r) {
                    throw r;
                }
                if (this.error instanceof Error err) {
                    throw err;
                }
                throw new IllegalStateException(this.error);
            }
            return this.result;
        }

        @Override
        public void run() {
            while (true) {
                List<Runnable> job = this.calls;
                if (job == null) {
                    LockSupport.park(this);
                    continue;
                }
                this.calls = null;
                long t0 = System.nanoTime();
                try {
                    for (Runnable call : job) {
                        call.run();
                    }
                    CALLS.add(job.size());
                    this.result = this.engine.runLightUpdates();
                } catch (Throwable t) {
                    this.error = t;
                }
                this.nanos = System.nanoTime() - t0;
                this.engine = null;
                this.done = true;
                LockSupport.unpark(this.waiter);
            }
        }
    }

    /** Sums so far, for {@link #report}. */
    public static long[] snapshot() {
        return new long[] { UPDATES.sum(), INLINE.sum(), CALLS.sum(), LANE_NANOS.sum(), WAIT_NANOS.sum() };
    }

    /** One line: updates with the layers at once, the block light calls they replayed, the lane's time and the light thread's wait for it. */
    public static String report(long[] before, long chunks) {
        long[] now = snapshot();
        double c = Math.max(1L, chunks);
        long updates = now[0] - before[0];
        return String.format(Locale.ROOT, "light layers at once: %d updates (%d with the block light on the light thread), %.1f block light calls per chunk;"
                + " lane %.3f ms per chunk, light thread waiting for it %.3f ms per chunk (%.2f ms per update)",
            updates, now[1] - before[1], (now[2] - before[2]) / c, (now[3] - before[3]) / 1e6 / c, (now[4] - before[4]) / 1e6 / c,
            updates == 0 ? 0.0 : (now[4] - before[4]) / 1e6 / updates);
    }

    /** Status lines for {@code /ga2 light}, totals since the start. */
    public static List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        lines.add("light layers at once: " + (!ENABLED ? "off (-Dgeneratoraccelerator2.lightLayers=false)"
            : !LightConfig.parallelLayers() ? "off (light.toml parallelLayers)"
            : Runtime.getRuntime().availableProcessors() <= 4 ? "off (four processor threads or fewer)"
            : !HookTargets.allowed(HookTargets.LIGHT_LAYERS) ? "off (another mod changes how light layers are updated)" : "on"));
        long updates = UPDATES.sum();
        lines.add(String.format(Locale.ROOT, "  %,d updates on two threads, %,d with the block light on the light thread; the light thread waited %.2f ms per update",
            updates, INLINE.sum(), updates == 0 ? 0.0 : WAIT_NANOS.sum() / 1e6 / updates));
        return lines;
    }
}
