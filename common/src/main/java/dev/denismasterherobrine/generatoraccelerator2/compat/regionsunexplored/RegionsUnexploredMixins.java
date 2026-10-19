package dev.denismasterherobrine.generatoraccelerator2.compat.regionsunexplored;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/**
 * Regions Unexplored mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin
 * plugin reads it.
 */
public final class RegionsUnexploredMixins {
    private RegionsUnexploredMixins() {}

    public static final String MOD_ID = "regions_unexplored";
    /**
     * Extends two of {@code WorldCarver.carveBlock}'s block tests. The grass test also matches the mod's peat and silt
     * grass ({@code fixGrassQuery}). The dirt test also matches the mod's peat and silt dirt ({@code fixDirtQuery}).
     */
    public static final String CARVER = "net.regions_unexplored.mixin.WorldCarverMixin";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.CARVER_REPLAY, CARVER, Set.of("fixGrassQuery", "fixDirtQuery"),
            "Regions Unexplored's grass and dirt still generate. GA2's cave carving places them."));
}
