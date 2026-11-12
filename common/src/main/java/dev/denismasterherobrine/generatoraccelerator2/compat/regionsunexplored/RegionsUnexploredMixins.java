package dev.denismasterherobrine.generatoraccelerator2.compat.regionsunexplored;

import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
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
    /**
     * {@code shouldSnow} returns false where the biome has no precipitation ({@code removeTundraSnow}, a return value change). The
     * second handler of that name changes the client's grass colour. GA2's freeze and snow skip only answers false for biomes where
     * vanilla's test is false already, and this change only turns true into false. So both hold together.
     */
    public static final String BIOME = "net.regions_unexplored.mixin.BiomeMixin";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.CARVER_REPLAY, CARVER, Set.of("fixGrassQuery", "fixDirtQuery"),
            "Regions Unexplored's grass and dirt still generate. GA2's cave carving places them."),
        new KnownMixins.Entry(MOD_ID, FeatureTargets.compatKey("climate"), BIOME, Set.of("removeTundraSnow"),
            "Regions Unexplored still keeps snow out of biomes without rain or snow. GA2 skips the snow test only where it is too warm to snow."));
}
