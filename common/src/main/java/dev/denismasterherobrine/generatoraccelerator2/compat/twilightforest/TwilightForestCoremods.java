package dev.denismasterherobrine.generatoraccelerator2.compat.twilightforest;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/**
 * Twilight Forest's coremods ({@code tf-asm} transformers) in code that GA2 takes over, named as {@code compat.Coremods} names
 * them ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it.
 */
public final class TwilightForestCoremods {
    private TwilightForestCoremods() {}

    public static final String MOD_ID = "twilightforest";
    /** The class that every worldgen call inserted by the coremods goes to. */
    public static final String HOOKS = "twilightforest.asmhooks.WorldgenHooks";
    /** The name {@code Coremods} gives the coremods' changes. It is derived from the hooks class name. */
    public static final String CHANGE = "coremod twilightforest/asmhooks/WorldgenHooks";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        // ChunkStatusTaskTransformer: WorldgenHooks.chunkBlanketing(chunk, region) right after the SURFACE step's generator call
        new KnownMixins.Entry(MOD_ID, HookTargets.SURFACE_STEP_BODY, CHANGE, Set.of("chunkBlanketing"),
            "Twilight Forest's dark forest canopy and glacier ice still generate. "
            + "GA2 runs them right after the surface step, on the same worker thread."));
}
