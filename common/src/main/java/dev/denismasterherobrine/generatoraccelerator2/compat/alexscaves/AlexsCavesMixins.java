package dev.denismasterherobrine.generatoraccelerator2.compat.alexscaves;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/** Alex's Caves mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it. */
public final class AlexsCavesMixins {
    private AlexsCavesMixins() {}

    public static final String MOD_ID = "alexscaves";
    /**
     * At the head of {@code ServerChunkCache.getChunk(x, z, status, load)}: hands the level's seed and dimension to its biome source
     * ({@code MultiNoiseBiomeSourceAccessor.setLastSampledSeed}, {@code setLastSampledDimension}), which Alex's Caves' biome lookup
     * reads. A chunk GA2 answers off the server thread gets the same two calls first ({@link AlexsCavesChunks}).
     */
    public static final String CHUNK_CACHE = "com.github.alexmodguy.alexscaves.mixin.ServerChunkCacheMixin";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.CHUNK_PRESENT_OFF_THREAD, CHUNK_CACHE, Set.of("ac_getChunk"),
            "Alex's Caves still learns the level's seed and dimension each time a chunk is asked for. GA2 tells it the same before it "
                + "answers a chunk the level already has."));
}
