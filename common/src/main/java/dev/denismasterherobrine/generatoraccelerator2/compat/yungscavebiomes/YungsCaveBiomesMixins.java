package dev.denismasterherobrine.generatoraccelerator2.compat.yungscavebiomes;

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

    /** The key of the aquifer changes that keep vanilla's values when statuses are computed in vanilla's order. */
    public static final String AQUIFER_ORDER_KEPT = "aquifer.orderKept";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, AQUIFER_ORDER_KEPT, AQUIFER, Set.of("noLavaAquifersInFrostedCaves"),
            "Frosted Caves water still generates as normal. To keep it, GA2's aquifer speedup runs in a reduced mode."));
}
