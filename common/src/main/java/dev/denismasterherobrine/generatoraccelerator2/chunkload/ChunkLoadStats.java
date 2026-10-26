package dev.denismasterherobrine.generatoraccelerator2.chunkload;

import it.unimi.dsi.fastutil.longs.Long2LongLinkedOpenHashMap;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.world.level.ChunkPos;

/** Counters and timings of chunk loading and sending, for {@code /ga2 chunks}. */
public final class ChunkLoadStats {
    private ChunkLoadStats() {}

    /** A count of events with their total and longest time. */
    public static final class Timer {
        private final LongAdder count = new LongAdder();
        private final LongAdder nanos = new LongAdder();
        private final AtomicLong max = new AtomicLong();

        public void record(long ns) {
            this.count.increment();
            this.nanos.add(ns);
            long m;
            while (ns > (m = this.max.get()) && !this.max.compareAndSet(m, ns)) {
                // another thread raised the maximum meanwhile
            }
        }

        public long count() {
            return this.count.sum();
        }

        public long totalNanos() {
            return this.nanos.sum();
        }

        void reset() {
            this.count.reset();
            this.nanos.reset();
            this.max.set(0);
        }

        /** "N, avg X ms, max Y ms". */
        String describe() {
            long n = this.count.sum();
            if (n == 0) {
                return "0";
            }
            return String.format(Locale.ROOT, "%,d, avg %s, max %s", n, ms(this.nanos.sum() / (double) n), ms(this.max.get()));
        }
    }

    // player tickets
    public static final LongAdder TICKETS_QUEUED = new LongAdder();
    public static final Timer SLOT_WAIT = new Timer();
    public static final Timer TICKET_READY = new Timer();
    // loads and unloads
    public static final Timer DISK_LOAD = new Timer();
    public static final LongAdder UNLOADS = new LongAdder();
    public static final LongAdder RELOADS = new LongAdder();
    public static final LongAdder RELOAD_AGE_MS = new LongAdder();
    // points of interest
    public static final LongAdder POI_PREFETCHED = new LongAdder();
    public static final LongAdder POI_PREFETCH_USED = new LongAdder();
    public static final LongAdder POI_READS = new LongAdder();
    public static final Timer POI_WAIT = new Timer();
    // server thread
    public static final Timer MAIN_TASKS = new Timer();
    public static final Timer ENTITY_ADDS = new Timer();
    public static final LongAdder ENTITY_CHUNKS = new LongAdder();
    public static final LongAdder ENTITY_DEFERRED = new LongAdder();
    // post-processing of chunks that become ticking (fluid.StillWater)
    public static final Timer POST_PROCESS = new Timer();
    public static final LongAdder FLUID_TICKS = new LongAdder();
    public static final LongAdder FLUID_TICKS_SKIPPED = new LongAdder();
    // chunk packets
    public static final Timer SEND = new Timer();
    public static final Timer PREPARE = new Timer();
    public static final LongAdder PREPARED = new LongAdder();
    public static final LongAdder PREPARED_USED = new LongAdder();
    public static final LongAdder PREPARED_STALE = new LongAdder();
    // the client in the same game (singleplayer, LAN host)
    public static final Timer CLIENT_CHUNK = new Timer();
    // the client's water blocks given to the section mesher, and those that show no face (fluid.StillWater)
    public static final LongAdder WATER_MESHED = new LongAdder();
    public static final LongAdder WATER_HIDDEN = new LongAdder();
    // the client's section occlusion graph (view.mixin.SectionOcclusionGraphMixin)
    public static final Timer GRAPH_REBUILD = new Timer();
    public static final LongAdder GRAPH_SHOWN = new LongAdder();
    public static final LongAdder GRAPH_TRAVEL = new LongAdder();
    public static final AtomicLong GRAPH_TRAVEL_MAX = new AtomicLong();
    public static final LongAdder GRAPH_KEPT = new LongAdder();
    public static final LongAdder GRAPH_KEPT_TIMES = new LongAdder();

    /** On the render thread, when a rebuilt graph is shown. {@code travel} is how far the camera moved meanwhile, in blocks. */
    public static void graphShown(long travel, int kept) {
        GRAPH_SHOWN.increment();
        GRAPH_TRAVEL.add(travel);
        if (travel > GRAPH_TRAVEL_MAX.get()) {
            GRAPH_TRAVEL_MAX.set(travel);
        }
        if (kept > 0) {
            GRAPH_KEPT.add(kept);
            GRAPH_KEPT_TIMES.increment();
        }
    }

    /** Chunks counted as reloaded when they are read back from disk within this time of their unload. */
    private static final long RELOAD_WINDOW_MS = 60_000;
    private static final int UNLOAD_MEMORY = 1 << 16;
    /** Recent unloads per dimension (server thread only): chunk position to the time of the unload, oldest first. */
    private static final Map<Object, Long2LongLinkedOpenHashMap> UNLOADED = new IdentityHashMap<>();
    private static volatile long since = System.nanoTime();

    /** On the server thread, when a chunk is unloaded and saved. */
    public static synchronized void unloaded(Object level, ChunkPos pos) {
        UNLOADS.increment();
        Long2LongLinkedOpenHashMap map = UNLOADED.computeIfAbsent(level, l -> new Long2LongLinkedOpenHashMap());
        map.putAndMoveToLast(pos.toLong(), System.currentTimeMillis());
        if (map.size() > UNLOAD_MEMORY) {
            map.removeFirstLong();
        }
    }

    /** The server stopped. Its levels are the map's keys, and a key keeps its whole world in memory. */
    public static synchronized void serverStopped() {
        UNLOADED.clear();
    }

    /** On the server thread, when a saved chunk is read back. */
    public static synchronized void loadedFromDisk(Object level, ChunkPos pos) {
        Long2LongLinkedOpenHashMap map = UNLOADED.get(level);
        if (map == null) {
            return;
        }
        long at = map.remove(pos.toLong());
        if (at != 0) {
            long age = System.currentTimeMillis() - at;
            if (age <= RELOAD_WINDOW_MS) {
                RELOADS.increment();
                RELOAD_AGE_MS.add(age);
            }
        }
    }

    public static synchronized void reset() {
        for (Timer t : List.of(SLOT_WAIT, TICKET_READY, DISK_LOAD, POI_WAIT, MAIN_TASKS, ENTITY_ADDS, POST_PROCESS, SEND, PREPARE, CLIENT_CHUNK, GRAPH_REBUILD)) {
            t.reset();
        }
        for (LongAdder a : List.of(TICKETS_QUEUED, UNLOADS, RELOADS, RELOAD_AGE_MS, POI_PREFETCHED, POI_PREFETCH_USED, POI_READS,
            ENTITY_CHUNKS, ENTITY_DEFERRED, FLUID_TICKS, FLUID_TICKS_SKIPPED, PREPARED, PREPARED_USED, PREPARED_STALE, WATER_MESHED, WATER_HIDDEN,
            GRAPH_SHOWN, GRAPH_TRAVEL, GRAPH_KEPT, GRAPH_KEPT_TIMES)) {
            a.reset();
        }
        GRAPH_TRAVEL_MAX.set(0);
        dev.denismasterherobrine.generatoraccelerator2.compat.chunky.ChunkyHolds.resetCounts();
        UNLOADED.clear();
        since = System.nanoTime();
    }

    /**
     * The report. {@code window} is the dimension's player ticket window, {@code inUse} the tickets of it still loading, or -1
     * when not known.
     */
    public static List<String> lines(int window, int inUse) {
        double seconds = Math.max(1e-3, (System.nanoTime() - since) / 1e9);
        List<String> out = new ArrayList<>();
        out.add(String.format(Locale.ROOT, "over the last %.0f s:", seconds));
        out.add("player tickets: window " + window + " (vanilla 4)" + (inUse >= 0 ? ", loading now " + inUse : "") + ", queued "
            + String.format(Locale.ROOT, "%,d", TICKETS_QUEUED.sum()) + "; wait for a slot " + SLOT_WAIT.describe()
            + "; slot to fully loaded " + TICKET_READY.describe());
        out.add("chunks read from disk on the server thread: " + DISK_LOAD.describe() + "; unloaded " + String.format(Locale.ROOT, "%,d", UNLOADS.sum())
            + ", read back within " + RELOAD_WINDOW_MS / 1000 + " s of their unload " + String.format(Locale.ROOT, "%,d", RELOADS.sum())
            + (RELOADS.sum() > 0 ? String.format(Locale.ROOT, " (%.1f s after it on average)", RELOAD_AGE_MS.sum() / 1000.0 / RELOADS.sum()) : ""));
        String holds = dev.denismasterherobrine.generatoraccelerator2.compat.chunky.ChunkyHolds.report();
        if (holds != null) {
            out.add(holds);
        }
        out.add("points of interest: read with the chunk " + String.format(Locale.ROOT, "%,d", POI_PREFETCHED.sum()) + " (used "
            + String.format(Locale.ROOT, "%,d", POI_PREFETCH_USED.sum()) + "); read on the server thread " + String.format(Locale.ROOT, "%,d", POI_READS.sum())
            + ", of them waited for the disk " + POI_WAIT.describe());
        out.add(String.format(Locale.ROOT, "server thread on chunk tasks: %s per second (%s)", ms(MAIN_TASKS.totalNanos() / seconds), MAIN_TASKS.describe()));
        out.add("entities of loaded chunks added: " + String.format(Locale.ROOT, "%,d", ENTITY_CHUNKS.sum()) + " chunks; ticks with additions "
            + ENTITY_ADDS.describe() + "; left for the next tick " + String.format(Locale.ROOT, "%,d", ENTITY_DEFERRED.sum()) + " times (budget "
            + ChunkLoadConfig.entityLoadBudgetNanos() / 1_000_000 + " ms)");
        long fluidTicks = FLUID_TICKS.sum();
        long skipped = FLUID_TICKS_SKIPPED.sum();
        out.add("chunks made ticking, post-processing on the server thread: " + POST_PROCESS.describe() + "; fluid updates "
            + String.format(Locale.ROOT, "%,d, of them still water left out %,d (%s)", fluidTicks, skipped, percent(skipped, fluidTicks))
            + (dev.denismasterherobrine.generatoraccelerator2.fluid.StillWater.postProcessSkipOn() ? "" : " (skip off)"));
        out.add("chunk packets: " + SEND.describe() + " each on the server thread, " + String.format(Locale.ROOT, "%.1f", SEND.count() / seconds)
            + " per second; block data encoded ahead " + String.format(Locale.ROOT, "%,d", PREPARED.sum()) + " (used "
            + String.format(Locale.ROOT, "%,d", PREPARED_USED.sum()) + ", changed meanwhile " + PREPARED_STALE.sum() + "), batches " + PREPARE.describe());
        if (CLIENT_CHUNK.count() > 0) {
            out.add("client: chunk packets applied on the render thread " + CLIENT_CHUNK.describe());
        }
        long meshed = WATER_MESHED.sum();
        if (meshed > 0) {
            long hidden = WATER_HIDDEN.sum();
            out.add(String.format(Locale.ROOT, "client: water blocks meshed %,d, of them hidden in water left out %,d (%s)", meshed, hidden, percent(hidden, meshed))
                + (dev.denismasterherobrine.generatoraccelerator2.fluid.StillWater.hiddenSkipOn() ? "" : " (skip off)"));
        }
        long shown = GRAPH_SHOWN.sum();
        if (GRAPH_REBUILD.count() > 0) {
            out.add("client: visible sections rebuilt " + GRAPH_REBUILD.describe() + "; camera moved until shown "
                + (shown > 0 ? String.format(Locale.ROOT, "avg %.0f, max %d blocks", GRAPH_TRAVEL.sum() / (double) shown, GRAPH_TRAVEL_MAX.get()) : "-")
                + "; sections at the view's edge kept " + String.format(Locale.ROOT, "%,d", GRAPH_KEPT.sum()) + " in "
                + String.format(Locale.ROOT, "%,d", GRAPH_KEPT_TIMES.sum()) + " of " + String.format(Locale.ROOT, "%,d", shown) + " rebuilds");
        }
        return out;
    }

    private static String percent(long part, long whole) {
        return whole > 0 ? String.format(Locale.ROOT, "%.1f%%", 100.0 * part / whole) : "-";
    }

    private static String ms(double nanos) {
        double ms = nanos / 1e6;
        return ms >= 10 ? String.format(Locale.ROOT, "%.0f ms", ms) : String.format(Locale.ROOT, "%.2f ms", ms);
    }
}
