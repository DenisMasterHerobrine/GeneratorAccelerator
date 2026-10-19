package dev.denismasterherobrine.generatoraccelerator2.compat.biolith;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/** Biolith mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it. */
public final class BiolithMixins {
    private BiolithMixins() {}

    public static final String MOD_ID = "biolith";
    /**
     * Runs the surface builders that mods register with Biolith around two of the per-column biome tests. These are the
     * eroded badlands test ({@code injectSurfaceBuilders}) and the frozen ocean test after the rule walk
     * ({@code injectLateSurfaceBuilders}).
     */
    public static final String SURFACE = "com.terraformersmc.biolith.impl.mixin.MixinSurfaceBuilder";
    /**
     * On the first {@code MultiNoiseBiomeSource.parameters()} call, builds the overworld and nether parameter lists with
     * Biolith's biomes injected ({@code injectEntries}). Later calls return that list. Every call enters the biome source's
     * monitor.
     */
    public static final String PARAMETERS = "com.terraformersmc.biolith.impl.mixin.MixinMultiNoiseBiomeSource";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.SURFACE_BUILD, SURFACE, Set.of("injectSurfaceBuilders", "injectLateSurfaceBuilders"),
            "Biolith's custom surfaces still generate. GA2 runs them inside its own faster surface step."),
        new KnownMixins.Entry(MOD_ID, HookTargets.BIOME_PARAMETERS, PARAMETERS, Set.of("injectEntries"),
            "Biolith's added biomes still appear. Once the biome list is built, GA2 reuses it without waiting on a lock."));
}
