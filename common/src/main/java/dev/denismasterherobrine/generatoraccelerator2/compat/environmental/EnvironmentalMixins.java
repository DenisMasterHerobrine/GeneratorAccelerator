package dev.denismasterherobrine.generatoraccelerator2.compat.environmental;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/**
 * Environmental mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it.
 */
public final class EnvironmentalMixins {
    private EnvironmentalMixins() {}

    public static final String MOD_ID = "environmental";
    /**
     * Raises stone in pine barrens: clears its flag at the head ({@code buildSurface}), notes each pine barrens column's raise before
     * the column's biome lookup ({@code collectRaises}) and places the stone after the loop ({@code generateRaisedStone}).
     */
    public static final String SURFACE = "com.teamabnormals.environmental.core.mixin.SurfaceSystemMixin";
    public static final String HEAD = "buildSurface";
    public static final String COLUMN = "collectRaises";
    public static final String END = "generateRaisedStone";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.SURFACE_BUILD, SURFACE, Set.of(HEAD, COLUMN, END),
            "Environmental's raised stone in pine barrens still generates. GA2 runs it inside its own faster surface step."),
        new KnownMixins.Entry(MOD_ID, HookTargets.GPU_SURFACE, SURFACE, Set.of(HEAD, COLUMN, END),
            "Environmental's raised stone in pine barrens still generates where the GPU builds the surface. GA2 notes the raises first and places the stone after."));
}
