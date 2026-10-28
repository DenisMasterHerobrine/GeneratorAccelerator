package dev.denismasterherobrine.generatoraccelerator2.compat.atmospheric;

import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/** Atmospheric mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it. */
public final class AtmosphericMixins {
    private AtmosphericMixins() {}

    public static final String MOD_ID = "atmospheric";
    /**
     * Redirects {@code biome.shouldSnow(level, pos)} in {@code SnowAndFreezeFeature.place}. Where the column is cold enough and dark
     * enough for snow and a barrel cactus stands below, Atmospheric swaps it for its snowy barrel cactus, written through the level.
     * Then vanilla's test runs.
     */
    public static final String SNOW_AND_FREEZE = "com.teamabnormals.atmospheric.core.mixin.SnowAndFreezeFeatureMixin";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, FeatureTargets.compatKey("snowAndFreeze"), SNOW_AND_FREEZE, Set.of("shouldSnow"),
            "Atmospheric's snowy barrel cacti still generate. GA2's snow and ice step places them."));
}
