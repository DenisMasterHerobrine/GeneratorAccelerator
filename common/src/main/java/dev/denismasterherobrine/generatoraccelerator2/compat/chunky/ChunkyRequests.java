package dev.denismasterherobrine.generatoraccelerator2.compat.chunky;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.scheduling.SchedulingConfig;
import org.slf4j.Logger;

/**
 * How many chunks Chunky requests at once. Chunky keeps at most {@code chunky.maxWorkingCount} requests open (50 by default). It
 * reads the system property once, when its first task starts. With GA2 a chunk takes about 100 ms from request to full chunk,
 * mostly waiting between threads, so 50 open requests hold a pregeneration far below what the CPU can do. GA2 sets the property
 * at mod construction, before any task. A {@code -Dchunky.maxWorkingCount} JVM argument is kept. Nothing is saved differently,
 * and without GA2 Chunky is back at its own default.
 */
public final class ChunkyRequests {
    private ChunkyRequests() {}

    public static final String MOD_ID = "chunky";
    static final String PROPERTY = "chunky.maxWorkingCount";
    /** Chunky's own default. Automatic counts never go below it. */
    static final int CHUNKY_DEFAULT = 50;
    /** On 16 threads 256 open requests were as fast as 512 (vanilla terrain, GPU on). */
    static final int PER_PROCESSOR = 16;
    static final int MAX = 1024;
    /**
     * Heap allowed per open request. More open requests keep more chunks loaded: halfway through a pregeneration of vanilla
     * terrain the live heap was 0.45 GB with 50, 1.2 GB with 256 and 3.6 GB with 512.
     */
    static final long HEAP_PER_REQUEST = 24L << 20;

    private static final Logger LOGGER = Ga2Log.get("Compat");

    public static void register() {
        String set = System.getProperty(PROPERTY);
        if (set != null) {
            LOGGER.info("Chunky requests in flight: {} from the JVM arguments -> kept", set);
            return;
        }
        Runtime rt = Runtime.getRuntime();
        int count = inFlight(SchedulingConfig.chunkyInFlight(), rt.availableProcessors(), rt.maxMemory());
        System.setProperty(PROPERTY, Integer.toString(count));
        LOGGER.info("Chunky requests in flight: {} (Chunky's default {})", count, CHUNKY_DEFAULT);
    }

    /** The configured count, or for 0 one from the processors and the heap. */
    static int inFlight(int configured, int processors, long maxHeap) {
        if (configured > 0) {
            return configured;
        }
        long byHeap = maxHeap / HEAP_PER_REQUEST;
        long count = Math.min(Math.min((long) PER_PROCESSOR * processors, byHeap), MAX);
        return (int) Math.max(CHUNKY_DEFAULT, count);
    }
}
