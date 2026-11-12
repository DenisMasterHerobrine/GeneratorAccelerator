package dev.denismasterherobrine.generatoraccelerator2.memory;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * How far the server thread is behind with unloading chunks: the holders waiting to unload ({@code ChunkMap.pendingUnloads}) and
 * the unload tasks queued ({@code unloadQueue}), summed over the levels. Unloads and their saves run on the server thread. When it
 * falls behind, the chunks stay in memory while the workers go on generating more, and a full heap stalls the server further.
 * One pack fell 4,340 ticks behind that way. While the backlog is high, Chunky's task thread keeps at most Chunky's own default of
 * requests open ({@code compat.chunky.ChunkyRequests}). Off with {@code -Dgeneratoraccelerator2.unloadBacklog=0}.
 */
public final class UnloadBacklog {
    private UnloadBacklog() {}

    /** Backlog that counts as high. It counts as normal again below half of it. */
    static final int HIGH = Integer.getInteger("generatoraccelerator2.unloadBacklog", 16384);

    private static final Map<Object, Integer> PER_LEVEL = new IdentityHashMap<>();
    private static volatile boolean high;
    private static volatile int total;
    private static volatile int peak;

    public static boolean high() {
        return high;
    }

    /** At the end of a level's {@code processUnloads}, on the server thread. */
    public static void report(Object level, int backlog) {
        Integer old = PER_LEVEL.put(level, backlog);
        int t = total - (old == null ? 0 : old) + backlog;
        total = t;
        if (t > peak) {
            peak = t;
        }
        if (HIGH > 0) {
            if (!high && t > HIGH) {
                high = true;
            } else if (high && t < HIGH / 2) {
                high = false;
            }
        }
    }

    /** The backlog now and its highest value since the last {@link #reset}. */
    public static String describe() {
        return String.format("%,d now, at most %,d", total, peak);
    }

    public static void reset() {
        peak = total;
    }

    /** The server stopped. Its levels go. */
    public static void serverStopped() {
        PER_LEVEL.clear();
        total = 0;
        peak = 0;
        high = false;
    }
}
