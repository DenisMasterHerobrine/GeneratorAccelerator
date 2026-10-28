package dev.denismasterherobrine.generatoraccelerator2.compat.blockswap;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/**
 * Block Swap mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it.
 */
public final class BlockSwapMixins {
    private BlockSwapMixins() {}

    public static final String MOD_ID = "blockswap";
    /** Writes the configured replacement instead of a swapped state, at the head of {@code LevelChunkSection.setBlockState} ({@code replaceState}). */
    public static final String SECTION = "corgitaco.blockswap.mixin.MixinChunkSection";
    /** Renames missing block ids in a section's palette before it is parsed, inside {@code ChunkSerializer.read} ({@code repairStates}). */
    public static final String SERIALIZER = "corgitaco.blockswap.mixin.MixinChunkSerializer";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.NOISE_SECTION_WRITES, SECTION, Set.of("replaceState"),
            "Block Swap still swaps its blocks. GA2 writes sections at once only where they hold no block Block Swap swaps."),
        new KnownMixins.Entry(MOD_ID, HookTargets.STORAGE_LOAD_PREP_UNCHANGED, SERIALIZER, Set.of("repairStates"),
            "Block Swap still repairs missing block ids in saved chunks. GA2 reads only the chunks it repairs the careful way."));
}
