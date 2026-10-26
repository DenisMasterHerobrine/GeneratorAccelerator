package dev.denismasterherobrine.generatoraccelerator2.scheduling;

import dev.denismasterherobrine.generatoraccelerator2.config.ConfigSpec;

/**
 * Config for running chunk steps off the single worldgen thread
 * ({@code config/generatoraccelerator2/scheduling.toml}). Output is identical either way: each step
 * still runs exactly once per chunk and only after the chunks it depends on are ready.
 */
public final class SchedulingConfig {
    private SchedulingConfig() {}

    public static final ConfigSpec SPEC;
    public static final ConfigSpec.BooleanValue PARALLEL_SURFACE;
    public static final ConfigSpec.BooleanValue PARALLEL_CARVERS;
    public static final ConfigSpec.BooleanValue PARALLEL_FEATURES;
    public static final ConfigSpec.BooleanValue PARALLEL_STRUCTURE_STARTS;
    public static final ConfigSpec.BooleanValue PARALLEL_STRUCTURE_REFERENCES;
    public static final ConfigSpec.BooleanValue PARALLEL_LIGHT_INIT;
    public static final ConfigSpec.IntValue FEATURES_GUARD_RADIUS;
    public static final ConfigSpec.BooleanValue DEDICATED_MAILBOX_THREADS;
    public static final ConfigSpec.BooleanValue STEADY_LIGHT_UPDATES;
    public static final ConfigSpec.IntValue CHUNKY_IN_FLIGHT;
    public static final ConfigSpec.IntValue CHUNKY_KEPT_CHUNKS;

    static {
        ConfigSpec.Builder b = new ConfigSpec.Builder();
        b.push("scheduling");
        PARALLEL_SURFACE = b.comment("Run the surface step of different chunks in parallel on the worker pool instead of one at a time on the",
            "single worldgen thread. Turn off if a mod's surface rules or chunk generator are not thread-safe.").define("parallelSurface", true);
        PARALLEL_CARVERS = b.comment("Run the carver step of different chunks in parallel on the worker pool instead of one at a time on the",
            "single worldgen thread. Turn off if a mod's carvers or chunk generator are not thread-safe.").define("parallelCarvers", true);
        PARALLEL_FEATURES = b.comment("Decorate chunks (features and structure pieces) in parallel on the worker pool. Decorations that could touch",
            "the same chunks never run at the same time (see featuresGuardRadius). Turn off if a mod's features are not thread-safe.").define("parallelFeatures", true);
        PARALLEL_STRUCTURE_STARTS = b.comment("Run the structure start step of different chunks in parallel on the worker pool. Vanilla structures that",
            "keep global state while they are laid out (strongholds, nether fortresses, end cities) are laid out one at a time.",
            "Turn off if a mod's structures are not thread-safe.").define("parallelStructureStarts", true);
        PARALLEL_STRUCTURE_REFERENCES = b.comment("Run the structure reference step (which records the structures reaching a chunk) on the worker pool instead of",
            "the single worldgen thread.").define("parallelStructureReferences", true);
        PARALLEL_LIGHT_INIT = b.comment("Run light initialization (finding a chunk's sky light sources) on the worker pool instead of the single worldgen",
            "thread, while no neighbour's decoration writes into the chunk. Needs parallelFeatures.").define("parallelLightInit", true);
        FEATURES_GUARD_RADIUS = b.comment("Chunk radius each parallel decoration keeps to itself. 1 keeps only the chunks it writes (3x3); 2 also keeps the",
            "ring around them that big features read, so no decoration sees another one half done.").defineInRange("featuresGuardRadius", 2, 1, 3);
        DEDICATED_MAILBOX_THREADS = b.comment("Run each dimension's chunk task sorter, worldgen mailbox and light engine on their own threads instead of the shared",
            "worker pool, where they would wait behind the parallel steps (light then only runs once the pool runs dry). Applies to",
            "dimensions loaded after the change. Ignored while Moonrise or C2ME runs the chunk system.").define("dedicatedMailboxThreads", true);
        STEADY_LIGHT_UPDATES = b.comment("Schedule light updates whenever the main thread polls for tasks. Vanilla skips them while the distance manager is",
            "busy, which during heavy generation means light only catches up after generation stops, holding finished chunks back.").define("steadyLightUpdates", true);
        CHUNKY_IN_FLIGHT = b.comment("How many chunks Chunky may request at once (its chunky.maxWorkingCount, 50 by default). A chunk spends most of its",
            "time waiting between threads, so more open requests keep the CPU busy. 0 picks a count from the processors and the memory.",
            "50 is Chunky's own default. A -Dchunky.maxWorkingCount JVM argument wins. Read at startup.").defineInRange("chunkyInFlight", 0, 0, 4096);
        CHUNKY_KEPT_CHUNKS = b.comment("How many chunks next to Chunky's requests stay loaded after Chunky lets them go, while Chunky will still need them.",
            "Without this they are saved, unloaded and read back from disk when Chunky reaches the next region. The world is the same",
            "either way. -1 picks a count from the memory (at most 4096). 0 turns this off.").defineInRange("chunkyKeptChunks", -1, -1, 65536);
        b.pop();
        SPEC = b.build();
    }

    private static boolean get(ConfigSpec.BooleanValue value, boolean fallback) {
        try {
            return SPEC.isLoaded() ? value.get() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public static boolean parallelSurface() { return get(PARALLEL_SURFACE, true); }
    public static boolean parallelCarvers() { return get(PARALLEL_CARVERS, true); }
    public static boolean parallelFeatures() { return get(PARALLEL_FEATURES, true); }
    public static boolean parallelStructureStarts() { return get(PARALLEL_STRUCTURE_STARTS, true); }
    public static boolean parallelStructureReferences() { return get(PARALLEL_STRUCTURE_REFERENCES, true); }
    public static boolean parallelLightInit() { return get(PARALLEL_LIGHT_INIT, true); }
    public static boolean dedicatedMailboxThreads() { return get(DEDICATED_MAILBOX_THREADS, true); }
    public static boolean steadyLightUpdates() { return get(STEADY_LIGHT_UPDATES, true); }

    public static int featuresGuardRadius() {
        try {
            return SPEC.isLoaded() ? FEATURES_GUARD_RADIUS.get() : 2;
        } catch (RuntimeException e) {
            return 2;
        }
    }

    public static int chunkyKeptChunks() {
        try {
            return SPEC.isLoaded() ? CHUNKY_KEPT_CHUNKS.get() : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    public static int chunkyInFlight() {
        try {
            return SPEC.isLoaded() ? CHUNKY_IN_FLIGHT.get() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
