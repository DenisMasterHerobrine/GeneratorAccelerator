package dev.denismasterherobrine.generatoraccelerator2.compat.ftbchunks;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/** FTB Chunks mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it. */
public final class FtbChunksMixins {
    private FtbChunksMixins() {}

    public static final String MOD_ID = "ftbchunks";
    /**
     * {@code FlowingFluid.getSpread}: FTB Chunks removes the directions into protected chunks from vanilla's result. The water whose
     * post-processing tick GA2 leaves out has no direction to spread to in vanilla ({@code fluid.StillWater}). With fewer directions
     * the tick still changes nothing.
     */
    public static final String FLUID_SPREAD = "dev.ftb.mods.ftbchunks.core.mixin.FlowingFluidMixin";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.FLUID_POST_PROCESS, FLUID_SPREAD, Set.of("onGetSpread"),
            "FTB Chunks still keeps water from flowing into protected chunks. The water GA2 leaves alone cannot flow anywhere anyway."));
}
