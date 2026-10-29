package dev.denismasterherobrine.generatoraccelerator2.compat.tectonic;

import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/** Tectonic mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it. */
public final class TectonicMixins {
    private TectonicMixins() {}

    public static final String MOD_ID = "tectonic";
    /**
     * {@code Biome.getHeightAdjustedTemperature} reads the position {@code snowStartOffset} blocks lower. The offset is Tectonic's
     * config snow line for taller terrain. The GPU's surface temperature applies the same offset ({@link TectonicSnow}).
     */
    public static final String BIOME = "dev.worldgen.tectonic.mixin.BiomeMixin";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.GPU_SURFACE, BIOME, Set.of("adjustSnowStart"),
            "Tectonic's raised snow line still applies. GA2's GPU surface uses the same offset."),
        // a snow line raised by a non-negative offset only warms each height, so vanilla's bound for always-warm biomes still holds
        new KnownMixins.Entry(MOD_ID, FeatureTargets.compatKey(FeatureTargets.CLIMATE), BIOME, Set.of("adjustSnowStart"),
            "Tectonic's raised snow line still applies. GA2 only skips the freeze and snow checks of biomes too warm for them even "
                + "at vanilla's lower snow line."));
}
