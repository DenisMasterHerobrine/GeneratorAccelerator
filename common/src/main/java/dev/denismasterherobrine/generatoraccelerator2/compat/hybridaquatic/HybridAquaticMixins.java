package dev.denismasterherobrine.generatoraccelerator2.compat.hybridaquatic;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/**
 * Hybrid Aquatic mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it.
 *
 * <p>Hybrid Aquatic makes its decorative bubble column hold no fluid, by wrapping the block tests in {@code FlowingFluid.canHoldFluid}
 * ({@code decorativeBubbleColumn_givesAirParity}). That only says no. A still-water tick GA2 leaves out ({@code fluid.StillWater})
 * never asks that test.
 */
public final class HybridAquaticMixins {
    private HybridAquaticMixins() {}

    public static final String MOD_ID = "hybrid_aquatic";
    public static final String FLOWING_FLUID = "dev.hybridlabs.aquatic.mixin.FlowingFluidMixin";
    /**
     * Places Hybrid Aquatic's replacement and sub-biomes over vanilla's answer, wrapping {@code MultiNoiseBiomeSource.getNoiseBiome}
     * ({@code placeBiomes}). The placement is set once while the server starts, from the config and the world's seed, and its answer
     * depends only on vanilla's answer, the position, the climate sample there and the seed. So the answer at a point stays the same, and
     * GA2 keeps it ({@code biome.BiomeAnswers}). The GPU hands the climate to the CPU's biome fill instead of deciding biomes itself.
     */
    public static final String BIOME_SOURCE = "dev.hybridlabs.aquatic.mixin.MultiNoiseBiomeSourceMixin";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.FLUID_POST_PROCESS, FLOWING_FLUID, Set.of("decorativeBubbleColumn_givesAirParity"),
            "Hybrid Aquatic's decorative bubble columns still hold no water. Water that cannot flow anywhere never gets that far, so GA2 still leaves its tick out."),
        new KnownMixins.Entry(MOD_ID, HookTargets.BIOME_ANSWERS, BIOME_SOURCE, Set.of("placeBiomes"),
            "Hybrid Aquatic's ocean biomes still generate. Its answer at a point never changes, so GA2 still keeps the biome answers it looked up."));
}
