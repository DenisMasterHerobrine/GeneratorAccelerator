package dev.denismasterherobrine.generatoraccelerator2.compat.structurify;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/** Structurify mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it. */
public final class StructurifyMixins {
    private StructurifyMixins() {}

    public static final String MOD_ID = "structurify";
    /**
     * {@code RandomSpreadStructurePlacement}: each read of {@code spacing} and {@code separation} in {@code getPotentialStructureChunk}
     * gives the configured value, the same value its handlers on the {@code spacing()} and {@code separation()} accessors give (read in
     * 1.0.21, 2.0.19, 2.0.34 and 2.0.43). GA2's version of the method then reads through the accessors ({@link #spreadAccessors}).
     */
    public static final String SPREAD = "com.faboslav.structurify.common.mixin.structure.placement.RandomSpreadStructurePlacementMixin";
    /** {@code StructurePlacement.salt()} and {@code frequency()} give the configured values. GA2's version calls {@code salt()} as vanilla's does. */
    public static final String PLACEMENT = "com.faboslav.structurify.common.mixin.structure.placement.StructurePlacementMixin";

    /** Whether Structurify's change was found in {@code getPotentialStructureChunk}. Set when the class is inspected, before it is used. */
    public static volatile boolean spreadAccessors;

    private static final String WHAT = "Structurify's structure spacing still applies. GA2's faster placement check reads the same configured spacing.";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.STRUCTURE_PLACEMENT_CHUNK, SPREAD,
            Set.of("getStartChunkGetSpacing", "getStartChunkGetSeparation", "getSpacing", "getSeparation"), WHAT),
        new KnownMixins.Entry(MOD_ID, HookTargets.STRUCTURE_PLACEMENT_CHUNK, PLACEMENT,
            Set.of("getSalt", "getFrequency", "applyAdditionalChunkRestrictionsGetFrequency", "isStructureChunkGetFrequency"), WHAT));
}