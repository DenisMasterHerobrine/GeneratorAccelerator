package dev.denismasterherobrine.generatoraccelerator2.scheduling;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import net.minecraft.Util;
import net.minecraft.server.level.ChunkHolder;

/**
 * Orders the chunk steps that run on the worker pool by how urgently their chunk is needed.
 *
 * <p>Vanilla's chunk system picks the next step of the nearest chunk first. Its task sorter orders by the chunk
 * holder's queue level. But anything the chunk system hands to the worker pool waits in the pool's first-in-first-out
 * queue. Vanilla hands over two steps per chunk. With GA2's parallel steps it is up to eight. So a chunk that the player
 * (or the main thread, waiting in {@code getChunk}) needs right now waits behind everything queued before it, once per
 * step. Here every such step goes into a bucket by queue level, and the pool gets one generic task per step. That task
 * runs the most urgent step queued at the moment the task starts. The pool runs as many steps as before, on the same
 * threads, and every step still runs exactly once. Only the order among steps that are ready at the same time changes.
 * The chunk system's own dependencies leave that order free.
 */
public final class StepLane {
    private StepLane() {}

    /** Queue levels go up to {@code ChunkLevel.MAX_LEVEL + 1}. Anything beyond shares the last bucket. */
    private static final int LEVELS = 64;
    /** The bucket for steps whose chunk holder is unknown (behind every known level, as vanilla's lowest priority). */
    public static final int UNKNOWN = LEVELS - 1;
    @SuppressWarnings("unchecked")
    private static final ConcurrentLinkedQueue<Runnable>[] BUCKETS = new ConcurrentLinkedQueue[LEVELS];
    /** The queue level of the step this thread is dispatching (noise and biomes pick their executor inside the generator). */
    private static final ThreadLocal<int[]> CURRENT = ThreadLocal.withInitial(() -> new int[] { -1 });

    public static final LongAdder STEPS = new LongAdder();
    /** {@code -Dgeneratoraccelerator2.stepLane=false} hands steps to the pool in arrival order, as before (for comparison). */
    public static final boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.stepLane"));

    static {
        for (int i = 0; i < LEVELS; i++) {
            BUCKETS[i] = new ConcurrentLinkedQueue<>();
        }
    }

    /** Queues {@code task} at {@code level} and gives the pool a task that runs the most urgent queued step. */
    public static void execute(int level, Runnable task) {
        if (!ENABLED) {
            Util.backgroundExecutor().execute(task);
            STEPS.increment();
            return;
        }
        ConcurrentLinkedQueue<Runnable> bucket = BUCKETS[Math.max(0, Math.min(UNKNOWN, level))];
        bucket.add(task);
        try {
            Util.backgroundExecutor().execute(StepLane::runOne);
        } catch (RejectedExecutionException e) {
            // the pool is shutting down: the step fails as vanilla's would
            bucket.remove(task);
            throw e;
        }
        STEPS.increment();
    }

    /** An executor queueing at {@code level}, for {@code CompletableFuture.supplyAsync}. */
    public static Executor executor(int level) {
        return task -> execute(level, task);
    }

    private static void runOne() {
        for (ConcurrentLinkedQueue<Runnable> bucket : BUCKETS) {
            Runnable task = bucket.poll();
            if (task != null) {
                task.run();
                return;
            }
        }
    }

    /** The queue level of the chunk a step works on ({@link #UNKNOWN} when the step does not run on vanilla's mailbox). */
    public static int level(StepCall call) {
        ChunkHolder holder = StepThreads.mailboxHolder();
        return holder != null && holder.getPos().equals(call.chunk().getPos()) ? holder.getQueueLevel() : UNKNOWN;
    }

    /** Runs {@code body} with the noise and biome executors queueing at {@code level} on this thread. */
    public static <T> T withLevel(int level, Supplier<T> body) {
        int[] current = CURRENT.get();
        int previous = current[0];
        current[0] = level;
        try {
            return body.get();
        } finally {
            current[0] = previous;
        }
    }

    /** The executor a generator's noise or biome step should use on this thread, or null for its own. */
    public static Executor currentExecutor() {
        if (!ENABLED) {
            return null;
        }
        int level = CURRENT.get()[0];
        return level < 0 ? null : executor(level);
    }
}
