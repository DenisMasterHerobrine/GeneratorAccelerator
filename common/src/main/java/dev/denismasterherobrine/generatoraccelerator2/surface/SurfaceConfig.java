package dev.denismasterherobrine.generatoraccelerator2.surface;

import dev.denismasterherobrine.generatoraccelerator2.config.ConfigSpec;

/**
 * Config for the surface stage ({@code config/generatoraccelerator2/surface.toml}). Output is
 * identical to vanilla with every combination. The switches exist to narrow down a problem.
 */
public final class SurfaceConfig {
    private SurfaceConfig() {}

    public static final ConfigSpec SPEC;
    public static final ConfigSpec.BooleanValue ENABLED;
    public static final ConfigSpec.BooleanValue COMPILE;
    public static final ConfigSpec.BooleanValue DEEP_BANDS;
    public static final ConfigSpec.BooleanValue SECTION_BULK;
    public static final ConfigSpec.BooleanValue BIOME_PROGRAMS;

    static {
        ConfigSpec.Builder b = new ConfigSpec.Builder();
        b.push("surface");
        ENABLED = b.comment("Build chunk surfaces with GA2's surface builder (rule compiler, cached biome zoom, batched block access)",
            "instead of vanilla's. Output is identical.").define("enabled", true);
        COMPILE = b.comment("Compile surface rules to bytecode; when off, the rule IR is interpreted.").define("compile", true);
        DEEP_BANDS = b.comment("Below each column's preliminary surface, evaluate only the part of the rules that can apply there",
            "(bedrock and deepslate gradients for vanilla rules).").define("deepBands", true);
        SECTION_BULK = b.comment("Rewrite whole sections at once where the deep part of the rules gives one result for every block.").define("sectionBulk", true);
        BIOME_PROGRAMS = b.comment("Compile, per biome met, the rules with that biome's biome conditions answered (mod packs test the biome against",
            "dozens of conditions per block).").define("biomePrograms", true);
        b.pop();
        SPEC = b.build();
    }

    /** Test overrides, used instead of the config when set. */
    public static volatile Boolean compileOverride;
    public static volatile Boolean deepBandsOverride;
    public static volatile Boolean sectionBulkOverride;
    public static volatile Boolean biomeProgramsOverride;

    private static boolean get(ConfigSpec.BooleanValue value, boolean fallback) {
        try {
            return SPEC.isLoaded() ? value.get() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public static boolean enabled() { return get(ENABLED, true); }
    public static boolean compile() { Boolean o = compileOverride; return o != null ? o : get(COMPILE, true); }
    public static boolean deepBands() { Boolean o = deepBandsOverride; return o != null ? o : get(DEEP_BANDS, true); }
    public static boolean sectionBulk() { Boolean o = sectionBulkOverride; return o != null ? o : get(SECTION_BULK, true); }
    public static boolean biomePrograms() { Boolean o = biomeProgramsOverride; return o != null ? o : get(BIOME_PROGRAMS, true); }
}
