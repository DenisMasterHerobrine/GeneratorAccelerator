package dev.denismasterherobrine.generatoraccelerator2.compat.alexscaves;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/**
 * Alex's Caves mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it.
 *
 * <p>Alex's Caves keeps its fluids out of waterloggable blocks, at the heads of {@code FlowingFluid.canHoldFluid} and
 * {@code spreadTo} ({@code ac_canHoldFluid}, {@code ac_spreadTo}). Both only say no. A still-water tick GA2 leaves out
 * ({@code fluid.StillWater}) reaches neither: vanilla's down test stops at the water below, and each side is source water, which
 * vanilla rules out before it asks the block.
 */
public final class AlexsCavesMixins {
    private AlexsCavesMixins() {}

    public static final String MOD_ID = "alexscaves";
    public static final String FLOWING_FLUID = "com.github.alexmodguy.alexscaves.mixin.FlowingFluidMixin";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.FLUID_POST_PROCESS, FLOWING_FLUID, Set.of("ac_canHoldFluid", "ac_spreadTo"),
            "Alex's Caves' fluids still stay out of waterloggable blocks. Water that cannot flow anywhere never gets that far, so GA2 still leaves its tick out."));
}
