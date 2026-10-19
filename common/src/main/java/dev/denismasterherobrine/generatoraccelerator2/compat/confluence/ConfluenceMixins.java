package dev.denismasterherobrine.generatoraccelerator2.compat.confluence;

import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/**
 * Confluence: Otherworld mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin
 * plugin reads it.
 */
public final class ConfluenceMixins {
    private ConfluenceMixins() {}

    public static final String MOD_ID = "confluence";
    /**
     * Wraps the {@code forEach} that places a {@code PlacedFeature} at each of its positions. For a tree feature with a
     * registry key, the wrapper's consumer first offers each position to Confluence's pine replacement. GA2's placement
     * pipeline calls this handler at each position it places at ({@code ConfluenceFeatures}).
     */
    public static final String PLACED_FEATURE = "org.confluence.mod.mixin.level.PlacedFeatureMixin";
    /**
     * Confluence's library makes the block state and biome codecs in {@code ChunkSerializer} retry a failed decode with
     * renamed ids. Encoding passes through unchanged ({@code coApply} returns its input). GA2's section tags only replace
     * encoding.
     */
    public static final String CHUNK_SERIALIZER = "org.confluence.lib.mixin.fixer.ChunkSerializerFixer";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, FeatureTargets.compatKey(FeatureTargets.PLACEMENT), PLACED_FEATURE, Set.of("wrap"),
            "Confluence's tree replacements still happen. GA2 offers every tree position to Confluence, as vanilla does."),
        new KnownMixins.Entry(MOD_ID, HookTargets.STORAGE_SECTION_CODEC, CHUNK_SERIALIZER, Set.of("fixBlockStateName", "fixBiomeName"),
            "Confluence's fixes for renamed blocks and biomes in old chunks still work. GA2 only speeds up saving, which Confluence does not change."));
}
