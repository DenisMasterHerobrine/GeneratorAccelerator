package dev.denismasterherobrine.generatoraccelerator2.carver;

import dev.denismasterherobrine.generatoraccelerator2.config.ConfigSpec;

/**
 * Config for the carver stage ({@code config/generatoraccelerator2/carvers.toml}). Output is identical
 * to vanilla with every setting.
 */
public final class CarverConfig {
    private CarverConfig() {}

    public static final ConfigSpec SPEC;
    public static final ConfigSpec.BooleanValue ENABLED;
    public static final ConfigSpec.IntValue PATH_CACHE_ENTRIES;

    static {
        ConfigSpec.Builder b = new ConfigSpec.Builder();
        b.push("carvers");
        ENABLED = b.comment("Carve with GA2's carver runner: vanilla cave, nether cave and canyon carvers are simulated once per start and",
            "replayed for every chunk they reach, instead of being re-simulated for each of up to 289 chunks. Output is identical.").define("enabled", true);
        PATH_CACHE_ENTRIES = b.comment("How many carver starts (with their tunnels) to keep per cache generation; two generations are kept.")
            .defineInRange("pathCacheEntries", 32768, 1024, 1 << 22);
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

    public static int pathCacheEntries() {
        try {
            return SPEC.isLoaded() ? PATH_CACHE_ENTRIES.get() : 32768;
        } catch (RuntimeException e) {
            return 32768;
        }
    }
}
