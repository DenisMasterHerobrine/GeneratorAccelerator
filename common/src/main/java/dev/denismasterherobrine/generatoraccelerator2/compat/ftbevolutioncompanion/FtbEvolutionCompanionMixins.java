package dev.denismasterherobrine.generatoraccelerator2.compat.ftbevolutioncompanion;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/**
 * FTB Evolution Companion mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin
 * reads it.
 */
public final class FtbEvolutionCompanionMixins {
    private FtbEvolutionCompanionMixins() {}

    public static final String MOD_ID = "ftbevolutioncompanion";
    /**
     * The spawn pyramid's biome. {@code getNoiseBiome} of the overworld's source answers the pack's pyramid biome inside a
     * configured block box ({@code PyramidBiome.override}). The box and the source are set when the level loads. So the answer
     * depends on the position alone, and a kept answer stays right.
     */
    public static final String MULTI_NOISE_SOURCE = "dev.ftb.mods.ftbevolutioncompanion.mixin.MultiNoiseBiomeSourceMixin";
    /** The same override on Lithostitched's biome injector source, which wraps the overworld's source. */
    public static final String INJECTOR_SOURCE = "dev.ftb.mods.ftbevolutioncompanion.mixin.InjectorBiomeSourceMixin";
    /** Mixin merges {@code ftbevolutioncompanion$pyramidBiome} as {@code handler$...$ftbevolutioncompanion$pyramidBiome}. */
    private static final String HANDLER = "pyramidBiome";
    private static final String WHAT = "The spawn pyramid keeps its biome. A biome that other mods ask for outside the chunks being "
        + "decorated is looked up once with the mod's code and kept.";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.BIOME_ANSWERS, MULTI_NOISE_SOURCE, Set.of(HANDLER), WHAT),
        new KnownMixins.Entry(MOD_ID, HookTargets.BIOME_ANSWERS, INJECTOR_SOURCE, Set.of(HANDLER), WHAT));
}
