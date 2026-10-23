package dev.denismasterherobrine.generatoraccelerator2.compat.eclipticseasons;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/** Ecliptic Seasons mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it. */
public final class EclipticSeasonsMixins {
    private EclipticSeasonsMixins() {}

    public static final String MOD_ID = "eclipticseasons";
    /**
     * Records each column's surface biome while the surface is built ({@code buildSurface_cacheBiome}, after the column's biome
     * lookup), small biomes by their climate's river biome. At the build's return it keeps the record on the chunk
     * ({@code buildSurface_cacheBiome_end}). It changes no block.
     */
    public static final String SURFACE = "com.teamtea.eclipticseasons.mixin.common.worldgen.MixinSurfaceSystem";
    static final String INIT = "eclipticseasons$buildSurface_cacheBiome_init";
    static final String COLUMN = "eclipticseasons$buildSurface_cacheBiome";
    static final String END = "eclipticseasons$buildSurface_cacheBiome_end";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.SURFACE_BUILD, SURFACE, Set.of(INIT, COLUMN, END),
            "Ecliptic Seasons' biome map of each chunk is still recorded. GA2 runs its code inside its own faster surface step."),
        new KnownMixins.Entry(MOD_ID, HookTargets.GPU_SURFACE, SURFACE, Set.of(INIT, COLUMN, END),
            "Ecliptic Seasons' biome map of each chunk is still recorded. GA2 records it on the CPU before the GPU's surface is written."));
}
