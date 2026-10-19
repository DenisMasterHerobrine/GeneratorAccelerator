package dev.denismasterherobrine.generatoraccelerator2.gpu;

import dev.denismasterherobrine.generatoraccelerator2.config.ConfigSpec;

/**
 * Config for GPU world generation ({@code config/generatoraccelerator2/gpu.toml}). The GPU decides exactly what GA2's CPU path
 * decides. Every chunk the GPU cannot do exactly, and every failure, goes to the CPU path.
 */
public final class GpuConfig {
    private GpuConfig() {}

    public static final ConfigSpec SPEC;
    public static final ConfigSpec.BooleanValue ENABLED;
    public static final ConfigSpec.IntValue DEVICE;
    public static final ConfigSpec.DoubleValue VRAM_SHARE;
    public static final ConfigSpec.IntValue BATCH_CHUNKS;
    public static final ConfigSpec.BooleanValue BIOMES;
    public static final ConfigSpec.BooleanValue SELF_TEST;
    public static final ConfigSpec.ConfigValue<String> PRECISION;

    static {
        ConfigSpec.Builder b = new ConfigSpec.Builder();
        b.push("gpu");
        ENABLED = b.comment("Generate biomes and terrain (the BIOMES and NOISE steps) on the GPU through OpenCL when a GPU passes the self-test",
            "at server start. Results are identical to the CPU path; chunks the GPU cannot do exactly (blending with old chunks, density",
            "functions it cannot express, other mods' changes to these steps) are generated on the CPU.").define("enabled", true);
        DEVICE = b.comment("Which OpenCL GPU to use: -1 picks one (a discrete GPU first), N the N-th GPU `/ga2 gpu` lists.").defineInRange("device", -1, -1, 64);
        VRAM_SHARE = b.comment("The share of the GPU's memory the region cache may use.").defineInRange("vramShare", 0.25, 0.05, 0.9);
        BATCH_CHUNKS = b.comment("Most chunks decided in one batch on the GPU (larger batches use the GPU better, smaller ones answer sooner).")
            .defineInRange("batchChunks", 64, 1, 256);
        BIOMES = b.comment("Also decide biomes on the GPU (the multi-noise biome source without other mods' changes).").define("biomes", true);
        SELF_TEST = b.comment("Compare the GPU with the CPU path on a few chunks of every dimension before using it there.").define("selfTest", true);
        PRECISION = b.comment("auto: decide the terrain's cells in float, each value with a bound on its error, and recompute in double every cell where",
            "an error could change a block (same result, faster on consumer GPUs); fp64: everything in double.").define("precision", "auto");
        b.pop();
        SPEC = b.build();
    }

    private static <T> T get(ConfigSpec.ConfigValue<T> v, T fallback) {
        try {
            return SPEC.isLoaded() ? v.get() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public static boolean enabled() {
        String p = System.getProperty("generatoraccelerator2.gpu");
        if (p != null) {
            return Boolean.parseBoolean(p);
        }
        return get(ENABLED, true);
    }

    public static int device() {
        return Integer.getInteger("generatoraccelerator2.gpu.device", get(DEVICE, -1));
    }

    public static double vramShare() {
        return get(VRAM_SHARE, 0.25);
    }

    public static int batchChunks() {
        return Integer.getInteger("generatoraccelerator2.gpu.batch", get(BATCH_CHUNKS, 64));
    }

    public static boolean biomes() {
        String p = System.getProperty("generatoraccelerator2.gpu.biomes");
        return p != null ? Boolean.parseBoolean(p) : get(BIOMES, true);
    }

    /** Whether the certified float path may be used ({@code precision = auto}). */
    public static boolean certified() {
        String p = System.getProperty("generatoraccelerator2.gpu.precision");
        return !"fp64".equalsIgnoreCase(p != null ? p : get(PRECISION, "auto"));
    }

    public static boolean selfTest() {
        String p = System.getProperty("generatoraccelerator2.gpu.selfTest");
        return p != null ? Boolean.parseBoolean(p) : get(SELF_TEST, true);
    }
}
