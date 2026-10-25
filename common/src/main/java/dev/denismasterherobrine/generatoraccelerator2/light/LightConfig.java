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
    public static final ConfigSpec.BooleanValue COPY_CHANGED_ONLY;
    public static final ConfigSpec.BooleanValue DIRECT_TASKS;
    public static final ConfigSpec.BooleanValue PARALLEL_LAYERS;

    static {
        ConfigSpec.Builder b = new ConfigSpec.Builder();
        b.push("light");
        FAST_ENGINE = b.comment("Run vanilla's light propagation with faster lookups (larger section and chunk caches, block states read",
            "straight from the chunk's sections, no shape work between two blocks without occlusion shapes) and find a chunk's",
            "light sources from its section palettes. Same propagation order and results.").define("fastEngine", true);
        COPY_CHANGED_ONLY = b.comment("After every light update, vanilla copies the light of every loaded section so other threads can read it.",
            "With this on, only the parts of the world the update changed are copied. The rest is shared with the last copy.",
            "Every thread reads the same light as with vanilla's copy.").define("copyChangedOnly", true);
        DIRECT_TASKS = b.comment("Hand light work for chunks straight to the light thread, and start a light update as soon as there is work.",
            "Vanilla passes chunks to the light thread one at a time through the chunk priority sorter, so chunks wait there while",
            "the light thread is busy. The light is the same. Takes effect for worlds loaded after the change.").define("directTasks", true);
        PARALLEL_LAYERS = b.comment("Update block light and sky light at the same time, on the light thread and one more thread per dimension.",
            "The two layers never share data, so the light is the same. Off on machines with four processor threads or fewer.",
            "Takes effect for worlds loaded after the change.").define("parallelLayers", true);
        b.pop();
        SPEC = b.build();
    }

    public static boolean parallelLayers() {
        try {
            return !SPEC.isLoaded() || PARALLEL_LAYERS.get();
        } catch (RuntimeException e) {
            return true;
        }
    }

    public static boolean directTasks() {
        try {
            return !SPEC.isLoaded() || DIRECT_TASKS.get();
        } catch (RuntimeException e) {
            return true;
        }
    }

    public static boolean copyChangedOnly() {
        try {
            return !SPEC.isLoaded() || COPY_CHANGED_ONLY.get();
        } catch (RuntimeException e) {
            return true;
        }
    }

    public static boolean fastEngine() {
        try {
            return !SPEC.isLoaded() || FAST_ENGINE.get();
        } catch (RuntimeException e) {
            return true;
        }
    }
}
