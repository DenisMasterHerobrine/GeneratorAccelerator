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

    /**
     * At the head of {@code MultiNoiseBiomeSource.getNoiseBiome(x, y, z, sampler)}: an Alex's Caves biome where the quart is in a
     * rare biome cell of the mod's Voronoi map and the climate fits. Reads the seed and dimension the source learnt, the mod's
     * biome map and its config, all set while the server starts ({@code AlexsCavesRules.ready}).
     */
    public static final String BIOME_SOURCE = "com.github.alexmodguy.alexscaves.mixin.MultiNoiseBiomeSourceMixin";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.CHUNK_PRESENT_OFF_THREAD, CHUNK_CACHE, Set.of("ac_getChunk"),
            "Alex's Caves still learns the level's seed and dimension each time a chunk is asked for. GA2 tells it the same before it "
                + "answers a chunk the level already has."),
        new KnownMixins.Entry(MOD_ID, HookTargets.BIOME_ANSWERS, BIOME_SOURCE, Set.of("ac_getNoiseBiomeCoords"),
            "Alex's Caves' biomes still appear. A biome that other mods ask for outside the chunks being decorated is looked up once "
                + "with Alex's Caves' code, after the mod has set up, and kept."));
}
