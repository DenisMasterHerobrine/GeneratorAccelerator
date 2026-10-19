package dev.denismasterherobrine.generatoraccelerator2.light;

import dev.denismasterherobrine.generatoraccelerator2.config.ConfigSpec;

/**
 * Config for the light engine ({@code config/generatoraccelerator2/light.toml}). Light is identical to vanilla's with
 * every setting.
 */
public final class LightConfig {
    private LightConfig() {}

    public static final ConfigSpec SPEC;
    public static final ConfigSpec.BooleanValue FAST_ENGINE;

    static {
        ConfigSpec.Builder b = new ConfigSpec.Builder();
        b.push("light");
        FAST_ENGINE = b.comment("Run vanilla's light propagation with faster lookups (larger section and chunk caches, block states read",
            "straight from the chunk's sections, no shape work between two blocks without occlusion shapes) and find a chunk's",
            "light sources from its section palettes. Same propagation order and results.").define("fastEngine", true);
        b.pop();
        SPEC = b.build();
    }

    public static boolean fastEngine() {
        try {
            return !SPEC.isLoaded() || FAST_ENGINE.get();
        } catch (RuntimeException e) {
            return true;
        }
    }
}
