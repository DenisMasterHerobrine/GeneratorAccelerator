package dev.denismasterherobrine.generatoraccelerator2.parallellight;

import dev.denismasterherobrine.generatoraccelerator2.config.ConfigSpec;

/**
 * Config for the parallel light propagation ({@code config/generatoraccelerator2/parallel-light.toml}). Light is identical to
 * vanilla's with every setting.
 */
public final class ParallelLightConfig {
    private ParallelLightConfig() {}

    public static final ConfigSpec SPEC;
    public static final ConfigSpec.BooleanValue ENABLED;
    public static final ConfigSpec.IntValue THREADS;
    public static final ConfigSpec.IntValue MIN_ENTRIES;
    public static final ConfigSpec.DoubleValue MIN_BUSY;

    static {
        ConfigSpec.Builder b = new ConfigSpec.Builder();
        b.push("parallelLight");
        ENABLED = b.comment("Spread a dimension's light propagation over several threads instead of its one light thread. The light thread",
            "still runs every light task in vanilla's order; only the flood fill of a large batch (new chunks' light, big block",
            "changes) is split, by section, between it and the helper threads. Light comes out identical to vanilla's.").define("enabled", true);
        THREADS = b.comment("Helper threads shared by all dimensions (the light thread itself always takes part). 0 = automatic: a quarter",
            "of the processor threads, at most 4, and none on a machine with four processor threads or fewer.").defineInRange("threads", 0, 0, 64);
        MIN_ENTRIES = b.comment("Smallest batch, in queued light changes, worth splitting; smaller batches run on the light thread alone.")
            .defineInRange("minEntries", 512, 1, Integer.MAX_VALUE);
        // Named minBusy up to 2.0.20.222, with 0.75. Light updates now start as soon as there is work, so a light thread that
        // keeps up is busy about 80% of the time. The new name keeps an old file's 0.75 from applying.
        MIN_BUSY = b.comment("Split batches only while the dimension's light thread is busy at least this share of the time (measured over",
            "half a second). Below it the light thread keeps up on its own, and helpers would only take processor time from world",
            "generation. 0 = always split large batches.").defineInRange("splitWhenBusy", 0.9, 0.0, 1.0);
        b.pop();
        SPEC = b.build();
    }

    public static boolean enabled() {
        try {
            return !SPEC.isLoaded() || ENABLED.get();
        } catch (RuntimeException e) {
            return true;
        }
    }

    public static int threads() {
        int configured;
        try {
            configured = SPEC.isLoaded() ? THREADS.get() : 0;
        } catch (RuntimeException e) {
            configured = 0;
        }
        if (configured > 0) {
            return configured;
        }
        int processors = Runtime.getRuntime().availableProcessors();
        // On four hardware threads the automatic count is one helper. A split batch waits for its slowest thread, so one
        // helper gains little, and it takes a processor from the three workers, the server thread and (on a client) rendering.
        return processors <= 4 ? 0 : Math.max(1, Math.min(4, processors / 4));
    }

    public static double minBusy() {
        try {
            return SPEC.isLoaded() ? MIN_BUSY.get() : 0.9;
        } catch (RuntimeException e) {
            return 0.9;
        }
    }

    public static int minEntries() {
        try {
            return SPEC.isLoaded() ? MIN_ENTRIES.get() : 512;
        } catch (RuntimeException e) {
            return 512;
        }
    }
}
