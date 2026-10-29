package dev.denismasterherobrine.generatoraccelerator2.scheduling;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.util.Util;

/**
 * Runs a level's ring position searches beside the chunk work instead of all at once ahead of it. For strongholds these
 * are 128 biome searches of 3249 samples each, started when the level is built.
 *
 * <p>Started all at once, the searches filled the worker pool's first-in-first-out queue ahead of everything a new world
 * does next. Loading the world's first chunks continues on the pool ({@code ChunkMap.readChunk}). So the spawn search
 * waited for every ring search before its first chunk existed, and a dimension built meanwhile waited on the pool in its
 * distance manager. Chunks near the origin never need the positions ({@code structure.RingBounds}). Until something
 * waits for the positions (a structure start farther out, {@code /locate}, an eye of ender), the searches run one at a
 * time on a thread of their own at low priority, outside the worker pool. A pregeneration near the spawn thus keeps
 * every worker. From then on the searches run on the pool, as many at once as the pool has workers. The searches are
 * the same, and each runs exactly once. Each search draws from its own forked random, so neither the order nor the
 * thread matters.
 *
 * <p>{@code -Dgeneratoraccelerator2.ringSearches=false} hands them to the pool all at once, as vanilla does.
 */
public final class RingSearches {
    private RingSearches() {}

    public static final boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.ringSearches"));
    private static final int BACKGROUND = 1;
    private static final ConcurrentLinkedQueue<Runnable> WAITING = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger RUNNING = new AtomicInteger();
    private static volatile int limit = BACKGROUND;

    /** The searches nothing waits for yet: one thread, below the workers' priority. */
    private static volatile Thread backgroundThread;
    private static volatile ExecutorService background;

    private static ExecutorService background() {
        ExecutorService b = background;
        if (b == null) {
            synchronized (RingSearches.class) {
                b = background;
                if (b == null) {
                    b = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "GA2 ring searches");
                        t.setDaemon(true);
                        t.setPriority(Thread.MIN_PRIORITY);
                        backgroundThread = t;
                        return t;
                    });
                    background = b;
                }
            }
        }
        return b;
    }

    /** The executor {@code generateRingPositions} hands its searches to. */
    public static Executor executor() {
        return RingSearches::execute;
    }

    private static void execute(Runnable search) {
        WAITING.add(search);
        drain();
    }

    /** Something waits for ring positions: run the searches still queued on the whole pool. */
    public static void hurry() {
        // something now waits for the search the background thread may be running. Raise it to the workers' priority
        Thread t = backgroundThread;
        if (t != null && RUNNING.get() > 0 && t.getPriority() != Thread.NORM_PRIORITY) {
            t.setPriority(Thread.NORM_PRIORITY);
        }
        int full = full();
        if (limit != full && !WAITING.isEmpty()) {
            limit = full;
            drain();
        }
    }

    private static int full() {
        return Util.backgroundExecutor().service() instanceof ForkJoinPool pool ? Math.max(1, pool.getParallelism()) : Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    }

    private static void drain() {
        for (;;) {
            int running = RUNNING.get();
            int max = limit;
            if (running >= max || WAITING.isEmpty()) {
                return;
            }
            if (!RUNNING.compareAndSet(running, running + 1)) {
                continue;
            }
            Runnable search = WAITING.poll();
            if (search == null) {
                RUNNING.decrementAndGet();
                continue;
            }
            boolean inBackground = max == BACKGROUND;
            Runnable run = () -> {
                try {
                    if (inBackground && limit == BACKGROUND) {
                        Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
                    }
                    search.run();
                } finally {
                    RUNNING.decrementAndGet();
                    if (WAITING.isEmpty()) {
                        // the next level's searches start in the background again
                        limit = BACKGROUND;
                    }
                    drain();
                }
            };
            try {
                (inBackground ? background() : Util.backgroundExecutor()).execute(run);
            } catch (RejectedExecutionException e) {
                // the pool is shutting down: finish here, so nothing waits forever for the positions
                run.run();
            }
        }
    }
}
