package dev.denismasterherobrine.generatoraccelerator2.features;

import java.util.LinkedHashMap;
import java.util.Map;
import dev.denismasterherobrine.generatoraccelerator2.config.ConfigSpec;

/**
 * Common config for the feature-stage optimizations ({@code config/generatoraccelerator2/features.toml}).
 * Every switch keeps the generated world identical. The switches exist to narrow down a problem or to
 * hand a part of decoration back to another mod. Read through safe getters because the harness runs
 * without a loaded config.
 */
public final class FeaturesConfig {
    private FeaturesConfig() {}

    public static final ConfigSpec SPEC;
    public static final ConfigSpec.BooleanValue ENABLED;
    public static final ConfigSpec.BooleanValue REGION_CACHE;
    public static final ConfigSpec.BooleanValue REGION_READS;
    public static final ConfigSpec.BooleanValue PLACEMENT_PIPELINE;
    public static final ConfigSpec.BooleanValue FAST_WRITES;
    public static final ConfigSpec.BooleanValue TREES;
    public static final ConfigSpec.BooleanValue VERIFY_CACHE;
    public static final ConfigSpec.BooleanValue DECORATION_SETUP;
    public static final ConfigSpec.BooleanValue BIOME_ZOOM;
    public static final ConfigSpec.BooleanValue PREDICATE_PROGRAMS;
    public static final ConfigSpec.BooleanValue SCAN_MODDED;
    public static final ConfigSpec.BooleanValue MODDED_FAST_PATHS;
    /** One switch per vanilla feature fast path, keyed by the config name. */
    public static final Map<String, ConfigSpec.BooleanValue> FAST_PATHS = new LinkedHashMap<>();

    public static final String[] FAST_PATH_NAMES = { "ore", "geode", "disk", "spring", "snowAndFreeze", "seagrass", "kelp", "multiface",
        "monsterRoom", "simpleBlock", "sculk" };

    static {
        ConfigSpec.Builder b = new ConfigSpec.Builder();
        b.push("features");
        ENABLED = b.comment("Master switch for every feature-stage optimization. Output is identical to vanilla either way.").define("enabled", true);
        REGION_CACHE = b.comment("Remember which chunk object each (x, z) of a generation region resolves to instead of resolving it on every block access.",
            "Turns itself off while a mod with its own chunk system runs (C2ME).").define("regionCache", true);
        REGION_READS = b.comment("Answer a generation region's getBlockState and getFluidState from the chunks it resolved and the chunk sections themselves",
            "(ProtoChunk's own reads), instead of resolving the chunk and asking it on every read. Needs regionCache.").define("regionReads", true);
        PLACEMENT_PIPELINE = b.comment("Run placement modifiers through an explicit loop instead of a lazy stream chain; vanilla modifiers are evaluated",
            "by transcribed code, unknown ones by their own getPositions.").define("placementPipeline", true);
        FAST_WRITES = b.comment("Let the fast paths write blocks through a transcription of WorldGenRegion.setBlock / ProtoChunk.setBlockState",
            "(same lock, same heightmap updates, same side effects).").define("fastWrites", true);
        TREES = b.comment("Place vanilla TreeFeature trees through the transcribed tree code (placers and decorators run unchanged).").define("trees", true);
        DECORATION_SETUP = b.comment("Hand a single-value biome palette over directly when decoration collects the biomes of the surrounding chunks.").define("decorationSetup", true);
        BIOME_ZOOM = b.comment("Answer a generation region's biome lookups from a per-region cache of the zoomed quart points (BiomeManager.getBiome's own arithmetic).").define("biomeZoom", true);
        PREDICATE_PROGRAMS = b.comment("Evaluate block predicate trees (all_of / any_of / not over block tests) as small programs: same answers, fewer",
            "allocations and calls, block lists checked by identity, a block read twice in one evaluation read once.").define("predicatePrograms", true);
        SCAN_MODDED = b.comment("Look through each modded feature class's code the first time it is placed for state shared between placements (static",
            "fields written, static collections changed). Such features place one at a time, as on vanilla's single worldgen thread;",
            "all others decorate in parallel. Off: every feature decorates in parallel, as before.").define("scanModdedFeatures", true);
        MODDED_FAST_PATHS = b.comment("Let a mod's subclass of a vanilla feature use GA2's fast path for that feature when it changes none of the",
            "methods the fast path does the work of.").define("moddedFastPaths", true);
        VERIFY_CACHE = b.comment("Development aid: compare every cached chunk lookup with the vanilla lookup and log differences.").define("verifyCache", false);
        b.push("fastPaths");
        for (String name : FAST_PATH_NAMES) {
            FAST_PATHS.put(name, b.comment("Transcribed fast path for the vanilla " + name + " feature.").define(name, true));
        }
        b.pop();
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

    public static boolean enabled() { return get(ENABLED, true); }
    public static boolean regionCache() { return get(REGION_CACHE, true); }
    /** {@code -Dgeneratoraccelerator2.features.regionReads=false} turns it off for A/B runs. */
    public static boolean regionReads() { return !"false".equals(System.getProperty("generatoraccelerator2.features.regionReads")) && get(REGION_READS, true); }
    public static boolean placementPipeline() { return get(PLACEMENT_PIPELINE, true); }
    public static boolean fastWrites() { return get(FAST_WRITES, true); }
    public static boolean trees() { return get(TREES, true); }
    public static boolean decorationSetup() { return get(DECORATION_SETUP, true); }
    public static boolean biomeZoom() { return get(BIOME_ZOOM, true); }
    public static boolean predicatePrograms() { return get(PREDICATE_PROGRAMS, true); }
    /** {@code -Dgeneratoraccelerator2.scanModdedFeatures=false} turns it off for A/B runs. */
    public static boolean scanModdedFeatures() { return !"false".equals(System.getProperty("generatoraccelerator2.scanModdedFeatures")) && get(SCAN_MODDED, true); }
    public static boolean moddedFastPaths() { return get(MODDED_FAST_PATHS, true); }
    public static boolean verifyCache() { return Boolean.getBoolean("generatoraccelerator2.features.verifyCache") || get(VERIFY_CACHE, false); }

    public static boolean fastPath(String name) {
        ConfigSpec.BooleanValue v = FAST_PATHS.get(name);
        return v == null || get(v, true);
    }

    /** One fast path's switch, found once by its handle ({@code FeaturesCompat.FastPath}). */
    static boolean on(ConfigSpec.BooleanValue fastPath) {
        return get(fastPath, true);
    }
}
