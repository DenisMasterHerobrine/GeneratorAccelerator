package dev.denismasterherobrine.generatoraccelerator2.compat.bettercaves;

import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/**
 * YUNG's Better Caves mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads
 * it.
 */
public final class BetterCavesMixins {
    private BetterCavesMixins() {}

    public static final String MOD_ID = "bettercaves";
    /**
     * The aquifer's {@code computeSubstance}. At its return the mod replaces every block below its "liquid altitude" that the
     * aquifer did not leave solid with the column's liquid block of its liquid regions (water, lava, or none, which makes the
     * block solid), as the original Better Caves flooded its caves. The regions are the mod's own per-chunk data, which it
     * makes from the chunk position alone. GA2's aquifer fast path, its whole-cell air test, the carvers' aquifer of a GPU
     * chunk and the GPU's block kernels apply the same step to their answers ({@code aquifer.LiquidRegions}).
     */
    public static final String AQUIFER = "com.yungnickyoung.minecraft.bettercaves.mixin.aquiferfix.AquiferMixin";

    /** The key of the aquifer changes GA2 runs itself as the mod's liquid-region step. */
    public static final String AQUIFER_LIQUID_REGIONS = "aquifer.liquidRegions";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, AQUIFER_LIQUID_REGIONS, AQUIFER, Set.of("fixAquiferLiquids", "setLiquidRegions"),
            "Better Caves' liquid regions still flood the caves below their altitude. GA2 applies the mod's own regions to its aquifer answers, on the CPU and on the GPU."));
}
