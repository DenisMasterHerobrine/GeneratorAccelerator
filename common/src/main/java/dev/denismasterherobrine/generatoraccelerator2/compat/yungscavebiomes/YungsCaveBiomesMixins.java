package dev.denismasterherobrine.generatoraccelerator2.compat.yungscavebiomes;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/**
 * YUNG's Cave Biomes mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin
 * reads it.
 */
public final class YungsCaveBiomesMixins {
    private YungsCaveBiomesMixins() {}

    public static final String MOD_ID = "yungscavebiomes";
    /**
     * The aquifer's {@code computeFluid}. A status whose cell centre lies in the mod's Frosted Caves keeps its level, with water
     * instead of its fluid. The handler makes one biome lookup per status, through the climate search's per-thread last result.
     * So a status computed in another order than vanilla's could differ on a tie. The aquifer fast path then only searches. It
     * computes exactly the statuses vanilla computes, in vanilla's order, with the mod's handler. The handler runs inside
     * vanilla's own method.
     */
    public static final String AQUIFER = "com.yungnickyoung.minecraft.yungscavebiomes.mixin.frosted_caves.AquiferMixin";

    /** Set once GA2's own Frosted Caves water is set up ({@code FrostedCaves}). Only then does GA2 cancel the mod's handler (Takeover). */
    private static volatile boolean frostedReplaced;

    public static boolean frostedReplaced() {
        return frostedReplaced;
    }

    static void replaceFrosted() {
        frostedReplaced = true;
    }

    /** The key of the aquifer changes that keep vanilla's values when statuses are computed in vanilla's order. */
    public static final String AQUIFER_ORDER_KEPT = "aquifer.orderKept";

    /**
     * Redirects the router's {@code finalDensity()} in the {@code NoiseChunk} constructor. With the mod's marble caves on, the
     * final density becomes a lerp towards the mod's own per-chunk function. With them off (2.0.5 never turns them on), the
     * handler returns the router's final density itself, so the compiled programs still compute it ({@link YungsCaveBiomesNoise}).
     */
    public static final String MARBLE_CAVES = "com.yungnickyoung.minecraft.yungscavebiomes.mixin.marble_caves.NoiseChunkMixin";
    static final String MARBLE_CAVES_HANDLER = "yungscavebiomes_rewireFinalDensity";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        // the handler's name is prefixed on 1.20.1 Forge
        new KnownMixins.Entry(MOD_ID, AQUIFER_ORDER_KEPT, AQUIFER, Set.of("noLavaAquifersInFrostedCaves", "yungscavebiomes_noLavaAquifersInFrostedCaves"),
            "Frosted Caves water still generates as normal. To keep it, GA2's aquifer speedup runs in a reduced mode."),
        new KnownMixins.Entry(MOD_ID, HookTargets.NOISE_FINAL_DENSITY, MARBLE_CAVES, Set.of(MARBLE_CAVES_HANDLER),
            "YUNG's Cave Biomes leaves the terrain's final density as it is while its marble caves are off. GA2 compiles it as usual."),
        new KnownMixins.Entry(MOD_ID, HookTargets.GPU_NOISE, MARBLE_CAVES, Set.of(MARBLE_CAVES_HANDLER),
            "YUNG's Cave Biomes leaves the terrain's final density as it is while its marble caves are off. The GPU computes it as usual."));
}
