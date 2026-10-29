package dev.denismasterherobrine.generatoraccelerator2.compat.modernfix;

import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/** ModernFix mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it. */
public final class ModernFixMixins {
    private ModernFixMixins() {}

    public static final String MOD_ID = "modernfix";
    /**
     * ModernFix's stronghold cache ({@code perf.cache_strongholds}). {@code generateRingPositions} answers with positions saved
     * in the world, or runs vanilla's computation on ModernFix's own thread pool and saves the result. Saved positions come from
     * vanilla's computation for the same placement, biome source and ring seed, which form ModernFix's cache key. Either way
     * the positions are vanilla's. The ring bound depends only on the placement's distance and vanilla's geometry, so the
     * bound holds. Nothing to set up.
     */
    public static final String STRONGHOLD_CACHE = "org.embeddedt.modernfix.common.mixin.perf.cache_strongholds.ChunkGeneratorMixin";

    /**
     * ModernFix's {@code perf.remove_biome_temperature_cache}. An overwrite of {@code Biome.getTemperature} that returns
     * {@code getHeightAdjustedTemperature} without vanilla's per-thread cache of that value. So every temperature, snow test and
     * rain test is vanilla's. The GPU's surface computes the height adjusted temperature itself, so the GPU's surface holds.
     */
    public static final String BIOME_TEMPERATURE = "org.embeddedt.modernfix.common.mixin.perf.remove_biome_temperature_cache.BiomeMixin";

    /**
     * ModernFix's {@code bugfix.chunk_deadlock}. In {@code ServerChunkCache.getChunk} on the server thread, a chunk that its holder is
     * turning into a full chunk right now is answered at once. GA2's early answer runs only off the server thread, so both apply. Newer
     * ModernFix builds keep the mixin in the common package, older Forge builds in the Forge one.
     */
    public static final String CHUNK_DEADLOCK = "org.embeddedt.modernfix.common.mixin.bugfix.chunk_deadlock.ServerChunkCache_CurrentLoadingMixin";
    public static final String CHUNK_DEADLOCK_FORGE = "org.embeddedt.modernfix.forge.mixin.bugfix.chunk_deadlock.ServerChunkCache_CurrentLoadingMixin";

    /**
     * ModernFix's {@code perf.dynamic_structure_manager}: the template repository becomes a cache with soft values. GA2 takes the mixin
     * over (compat/Takeover) and makes the same cache itself ({@code structure.TemplateLoads}). A later ModernFix also changes the
     * repository's {@code get}, which turned GA2's template loads off in All the Mods 11.
     */
    public static final String DYNAMIC_STRUCTURES = "org.embeddedt.modernfix.common.mixin.perf.dynamic_structure_manager.StructureManagerMixin";

    private static final String DEADLOCK = "ModernFix's chunk deadlock fix still works. It acts on the server thread, and GA2 only hands ready chunks to other threads.";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, FeatureTargets.compatKey("climate"), BIOME_TEMPERATURE, Set.of(KnownMixins.overwrite("getTemperature")),
            "ModernFix's biome temperature change still works. It gives the same values, so GA2 still skips freezing and snow only where it is too warm."),
        new KnownMixins.Entry(MOD_ID, HookTargets.CHUNK_PRESENT_OFF_THREAD, CHUNK_DEADLOCK, Set.of("checkCurrentlyLoading"), DEADLOCK),
        new KnownMixins.Entry(MOD_ID, HookTargets.CHUNK_PRESENT_OFF_THREAD, CHUNK_DEADLOCK_FORGE, Set.of("checkCurrentlyLoading"), DEADLOCK),
        new KnownMixins.Entry(MOD_ID, HookTargets.STRUCTURE_RING_BOUND, STRONGHOLD_CACHE, Set.of("cacheRingPositions", "useDedicatedService"),
            "ModernFix's stronghold cache still works. Stronghold positions stay the same as in vanilla."),
        new KnownMixins.Entry(MOD_ID, HookTargets.GPU_SURFACE, BIOME_TEMPERATURE, Set.of(KnownMixins.overwrite("getTemperature")),
            "ModernFix's biome temperature change still works. It gives the same values, so GA2's GPU surface stays exact."));
}
