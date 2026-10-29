package dev.denismasterherobrine.generatoraccelerator2.compat.chunky;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.scheduling.SchedulingConfig;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.dimension.LevelStem;
import org.slf4j.Logger;

/**
 * How many chunks Chunky requests at once. Chunky keeps at most {@code chunky.maxWorkingCount} requests open (50 by default). It
 * reads the system property once, when its first task starts. With GA2 a chunk takes about 100 ms from request to full chunk,
 * mostly waiting between threads, so 50 open requests hold a pregeneration far below what the CPU can do. GA2 sets the property
 * at mod construction, before any task, for a world of vanilla height. At server start, still before any task, it sets it again
 * for the overworld's height: a taller world's chunks hold more sections, so fewer requests fit in the heap. A
 * {@code -Dchunky.maxWorkingCount} JVM argument is kept. Nothing is saved differently, and without GA2 Chunky is back at its own
 * default.
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
     * Heap allowed per open request in a world of {@link #BASE_SECTIONS} sections. More open requests keep more chunks loaded:
     * halfway through a pregeneration of vanilla terrain the live heap was 0.45 GB with 50, 1.2 GB with 256 and 3.6 GB with 512.
     */
    static final long HEAP_PER_REQUEST = 24L << 20;
    /** The vanilla overworld's height in sections (384 blocks), where {@link #HEAP_PER_REQUEST} was measured. */
    static final int BASE_SECTIONS = 24;

    private static final Logger LOGGER = Ga2Log.get("Compat");

    /** GA2 set the property, not a JVM argument. A server start may then set it again for the world's height. */
    private static volatile boolean ours;

    public static void register() {
        String set = System.getProperty(PROPERTY);
        if (set != null) {
            LOGGER.info("Chunky requests in flight: {} from the JVM arguments -> kept", set);
            return;
        }
        ours = true;
        int count = count(BASE_SECTIONS);
        System.setProperty(PROPERTY, Integer.toString(count));
        LOGGER.info("Chunky requests in flight: {} (Chunky's default {})", count, CHUNKY_DEFAULT);
    }

    /**
     * The count again for the overworld's height, at server start. Chunky has started no task yet then. Chunky reads the property
     * once per game, so a later world of another height keeps the first world's count.
     */
    public static void serverStarting(MinecraftServer server) {
        if (!ours) {
            return;
        }
        int sections = overworldSections(server);
        int count = count(sections);
        String value = Integer.toString(count);
        if (!value.equals(System.getProperty(PROPERTY))) {
            System.setProperty(PROPERTY, value);
            LOGGER.info("Chunky requests in flight: {} for a world {} blocks high", count, sections * 16);
        }
    }

    private static int count(int sections) {
        Runtime rt = Runtime.getRuntime();
        return inFlight(SchedulingConfig.chunkyInFlight(), rt.availableProcessors(), rt.maxMemory(), sections);
    }

    /** The overworld's height in sections, from its dimension type. {@link #BASE_SECTIONS} when the world has no overworld. */
    static int overworldSections(MinecraftServer server) {
        try {
            LevelStem overworld = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).get(LevelStem.OVERWORLD);
            return overworld != null ? overworld.type().value().height() >> 4 : BASE_SECTIONS;
        } catch (RuntimeException e) {
            return BASE_SECTIONS;
        }
    }

    /**
     * The configured count, or for 0 one from the processors, the heap and the world's height in sections. A request's heap grows
     * with the height. A world lower than vanilla's keeps the vanilla figure.
     */
    static int inFlight(int configured, int processors, long maxHeap, int sections) {
        if (configured > 0) {
            return configured;
        }
        long byHeap = maxHeap / (HEAP_PER_REQUEST * Math.max(sections, BASE_SECTIONS) / BASE_SECTIONS);
        long count = Math.min(Math.min((long) PER_PROCESSOR * processors, byHeap), MAX);
        return (int) Math.max(CHUNKY_DEFAULT, count);
    }
}
