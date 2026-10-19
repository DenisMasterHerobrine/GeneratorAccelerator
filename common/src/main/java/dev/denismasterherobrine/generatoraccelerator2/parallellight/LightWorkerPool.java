package dev.denismasterherobrine.generatoraccelerator2.parallellight;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;

/**
 * Helper threads for parallel light propagation, shared by every dimension. A light thread reserves the free helpers for one
 * batch and works alongside them. While all helpers are busy (e.g. with another dimension's batch), a batch runs on its light
 * thread alone. Idle helpers are parked. Helpers never run anything but light propagation.
 */
final class LightWorkerPool {
    private static final Logger LOGGER = Ga2Log.get("Light");
    private static volatile LightWorkerPool instance;

    private final Helper[] helpers;

    private LightWorkerPool(Helper[] helpers) {
        this.helpers = helpers;
    }

    /** The pool, grown to at least {@code size} helpers. */
    static LightWorkerPool get(int size) {
        LightWorkerPool pool = instance;
        if (pool != null && pool.helpers.length >= size) {
            return pool;
        }
        synchronized (LightWorkerPool.class) {
            pool = instance;
            if (pool == null || pool.helpers.length < size) {
                Helper[] helpers = new Helper[size];
                int existing = pool == null ? 0 : pool.helpers.length;
                if (existing > 0) {
                    System.arraycopy(pool.helpers, 0, helpers, 0, existing);
                }
                // Same priority as the light thread, above the worker pool, because a split batch waits for its slowest
                // helper. Never above the thread that starts the helpers.
                int priority = Math.min(Thread.NORM_PRIORITY + 2, Thread.currentThread().getPriority());
                for (int i = existing; i < size; i++) {
                    helpers[i] = new Helper(i + 1, priority);
                }
                pool = new LightWorkerPool(helpers);
                instance = pool;
            }
            return pool;
        }
    }

    /** Reserves up to {@code want} idle helpers. */
    Helper[] reserve(int want) {
        List<Helper> got = new ArrayList<>(want);
        for (Helper h : this.helpers) {
            if (got.size() >= want) {
                break;
            }
            if (h.state.compareAndSet(Helper.IDLE, Helper.RESERVED)) {
                got.add(h);
            }
        }
        return got.toArray(new Helper[0]);
    }

    static final class Helper implements Runnable {
        static final int IDLE = 0;
        static final int RESERVED = 1;

        final AtomicInteger state = new AtomicInteger(IDLE);
        private volatile Propagator task;
        private final Thread thread;

        Helper(int id, int priority) {
            this.thread = new Thread(this, "GA2 Light Helper #" + id);
            this.thread.setDaemon(true);
            this.thread.setPriority(priority);
            this.thread.setUncaughtExceptionHandler((t, e) -> LOGGER.error("uncaught exception on {}", t.getName(), e));
            this.thread.start();
        }

        void start(Propagator propagator) {
            this.task = propagator;
            LockSupport.unpark(this.thread);
        }

        @Override
        public void run() {
            while (true) {
                Propagator p = this.task;
                if (p == null) {
                    LockSupport.park(this);
                    continue;
                }
                this.task = null;
                try {
                    p.work();
                } catch (Throwable t) {
                    p.job.fail(t);
                } finally {
                    this.state.set(IDLE);
                    p.job.helperDone();
                }
            }
        }
    }
}
