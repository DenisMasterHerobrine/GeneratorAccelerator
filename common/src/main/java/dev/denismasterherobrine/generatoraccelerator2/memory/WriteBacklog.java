package dev.denismasterherobrine.generatoraccelerator2.memory;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Chunk saves the IO threads have not written yet: {@code IOWorker.pendingWrites} of each chunk storage. Each one holds the
 * chunk's whole tag until it is written. Vanilla's IO thread writes only while no load or save request waits. Steady requests
 * or a slow disk therefore let the saves pile up while the workers go on generating. A TerraFirmaGreg pregeneration on a
 * swapping system had 38,000 saves waiting after six minutes, 5 GB of its 12 GB heap.
 *
 * <p>Above {@link #WRITE_ALONG} saves waiting in one storage, its IO thread writes the oldest one after each request too
 * ({@code mixin.storage.IOWorkerMixin}). Loads then wait on the disk, and with them the generation. While the backlog over all
 * storages is high, Chunky's task thread also waits before each new request ({@code compat.chunky.ChunkyRequests}). Off with
 * {@code -Dgeneratoraccelerator2.writeBacklog=0}.
 */
public final class WriteBacklog {
    private WriteBacklog() {}

    /** Saves waiting over all chunk storages that count as high. The backlog counts as normal again below half of it. */
    static final int HIGH = Integer.getInteger("generatoraccelerator2.writeBacklog", 4096);
    /**
     * Saves waiting in one storage from which its IO thread writes one after each request. Half of {@link #HIGH}: at an eighth
     * it wrote along all the time at 1,000 chunks/s on a fast disk and cost about 5%.
     */
    public static final int WRITE_ALONG = writeAlongFrom(HIGH);

    /** Saves written after a request because many were waiting. */
    public static final LongAdder WRITTEN_ALONG = new LongAdder();
    private static final HookTargets.Hook HOOK = HookTargets.hook(HookTargets.STORAGE_WRITE_ALONG);

    /** What the IO worker mixin gives this class. */
    public interface Storage {
        /** Saves waiting. Read without a lock from other threads, so a recent value. */
        int ga2$pendingWrites();
    }

    private static final Set<Storage> STORAGES = ConcurrentHashMap.newKeySet();
    private static volatile boolean high;
    private static volatile int peak;

    static int writeAlongFrom(int high) {
        return high > 0 ? Math.max(64, high / 2) : Integer.MAX_VALUE;
    }

    /** Whether an IO thread with this many saves waiting writes one after its request. Notes the backlog's peak then. */
    public static boolean writeAlong(int pending) {
        if (pending <= WRITE_ALONG || !HOOK.allowed()) {
            return false;
        }
        total();
        return true;
    }

    /** A chunk storage's IO worker was made. */
    public static void opened(Storage storage) {
        if (HIGH > 0) {
            STORAGES.add(storage);
        }
    }

    /** The IO worker is closing. It writes its saves before it closes. */
    public static void closed(Storage storage) {
        STORAGES.remove(storage);
    }

    /** Saves waiting now over all chunk storages. */
    public static int total() {
        int total = 0;
        for (Storage storage : STORAGES) {
            total += storage.ga2$pendingWrites();
        }
        if (total > peak) {
            peak = total;
        }
        return total;
    }

    /** Whether the backlog is high. It stays high until it is below half of {@link #HIGH}. */
    public static boolean high() {
        if (HIGH <= 0) {
            return false;
        }
        high = next(high, total(), HIGH);
        return high;
    }

    static boolean next(boolean wasHigh, int total, int limit) {
        return wasHigh ? total >= limit / 2 : total > limit;
    }

    /** The backlog now, its highest value since the last {@link #reset} and the saves written along with requests. */
    public static String describe() {
        return String.format(Locale.ROOT, "%,d now, at most %,d, written along with requests %,d", total(), peak, WRITTEN_ALONG.sum());
    }

    public static void reset() {
        peak = total();
        WRITTEN_ALONG.reset();
    }

    /** The server stopped. Its storages are closed. */
    public static void serverStopped() {
        STORAGES.clear();
        high = false;
        peak = 0;
    }
}
