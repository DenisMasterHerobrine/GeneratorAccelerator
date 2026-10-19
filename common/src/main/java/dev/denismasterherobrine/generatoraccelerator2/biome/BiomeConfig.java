package dev.denismasterherobrine.generatoraccelerator2.biome;

import dev.denismasterherobrine.generatoraccelerator2.config.ConfigSpec;

/**
 * Config for the biome stage ({@code config/generatoraccelerator2/biomes.toml}). Output is identical to
 * vanilla with every setting.
 */
public final class BiomeConfig {
    private BiomeConfig() {}

    public static final ConfigSpec SPEC;
    public static final ConfigSpec.BooleanValue FLAT_CLIMATE_SEARCH;

    static {
        ConfigSpec.Builder b = new ConfigSpec.Builder();
        b.push("biomes");
        FLAT_CLIMATE_SEARCH = b.comment("Search the climate tree (which biome a climate point belongs to) through flat arrays, with vanilla's exact",
            "traversal and tie-breaking, instead of walking the tree objects.").define("flatClimateSearch", true);
        b.pop();
        SPEC = b.build();
    }

    public static boolean flatClimateSearch() {
        try {
            return !SPEC.isLoaded() || FLAT_CLIMATE_SEARCH.get();
        } catch (RuntimeException e) {
            return true;
        }
    }
}
