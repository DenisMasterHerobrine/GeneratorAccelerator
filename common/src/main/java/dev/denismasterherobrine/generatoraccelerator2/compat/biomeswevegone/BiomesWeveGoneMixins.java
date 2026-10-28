package dev.denismasterherobrine.generatoraccelerator2.compat.biomeswevegone;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/**
 * Oh The Biomes We've Gone mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin
 * plugin reads it.
 */
public final class BiomesWeveGoneMixins {
    private BiomesWeveGoneMixins() {}

    public static final String MOD_ID = "biomeswevegone";
    /**
     * Also runs vanilla's eroded badlands extension for the mod's shattered glacier and eroded borealis biomes
     * ({@code injectShatteredGlacierExtension}). It runs before the heightmap read that starts the rule walk. The mixin's
     * other change to SurfaceSystem is the band lookup that the mod's bands rule asks for. buildSurface does not call it.
     */
    public static final String SURFACE = "net.potionstudios.biomeswevegone.mixin.SurfaceSystemMixin";
    /** Builds crag garden and basalt barrera terrain at the return of the SURFACE task, after its generator call ({@code injectCragTerrain}). */
    public static final String STEP = "net.potionstudios.biomeswevegone.mixin.ChunkStatusMixin";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.SURFACE_BUILD, SURFACE, Set.of("injectShatteredGlacierExtension"),
            "Shattered Glacier and Eroded Borealis surfaces still generate. GA2 runs them inside its own faster surface step."),
        new KnownMixins.Entry(MOD_ID, HookTargets.GPU_SURFACE, SURFACE, Set.of("injectShatteredGlacierExtension"),
            "Shattered Glacier and Eroded Borealis surfaces still generate. Their chunks build the surface on the CPU, the others on the GPU."),
        new KnownMixins.Entry(MOD_ID, HookTargets.SURFACE_STEP_BODY, STEP, Set.of("injectCragTerrain"),
            "Crag Garden and Basalt Barrera terrain still generates. GA2 builds it on the same worker thread, right after the surface step."));
}
