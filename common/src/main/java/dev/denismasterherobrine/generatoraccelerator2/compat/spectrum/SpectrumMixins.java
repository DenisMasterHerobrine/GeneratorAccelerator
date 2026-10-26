package dev.denismasterherobrine.generatoraccelerator2.compat.spectrum;

import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.pastel.PastelGeodes;
import java.util.List;
import java.util.Set;

/** Spectrum mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it. */
public final class SpectrumMixins {
    private SpectrumMixins() {}

    public static final String MOD_ID = "spectrum";
    /**
     * At the tail of {@code GeodeFeature.place}, after a geode is placed in full: gemstone ores of the geode's inner block around the
     * geode, from the mod's data, written through the level and drawn from the feature's random. The same handler as Pastel's (Pastel
     * is its port), read only from the context.
     */
    public static final String GEODE_ORES = "de.dafuqs.spectrum.mixin.GeodesGenerateWithGemstoneOresMixin";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, FeatureTargets.compatKey("geode"), GEODE_ORES, Set.of("generate"),
            "Spectrum's gemstone ores around geodes still generate. GA2's faster geodes place them at the same point vanilla does."));

    public static void register() {
        CompatAdapters.register(FeatureTargets.compatKey("geode"), GEODE_ORES,
            PastelGeodes::bindTail);
    }
}
