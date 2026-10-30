package dev.denismasterherobrine.generatoraccelerator2.compat.railways;

import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/** Create: Steam 'n' Rails mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it. */
public final class RailwaysMixins {
    private RailwaysMixins() {}

    public static final String MOD_ID = "railways";
    /**
     * {@code Biome.shouldSnow}: snow may also lie on a track, not only on air. Vanilla's method returns before that test where the
     * biome is too warm to snow. GA2 skips the method only for biomes too warm at every height ({@code features.fast.WarmBiomes}).
     */
    public static final String SNOW_ON_TRACKS = "com.railwayteam.railways.mixin.MixinBiome";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, FeatureTargets.compatKey("climate"), SNOW_ON_TRACKS, Set.of("canSnowOnTracks"),
            "Snow still settles on Steam 'n' Rails tracks. GA2 only skips the snow check where it is too warm for snow."));
}
