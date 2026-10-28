package dev.denismasterherobrine.generatoraccelerator2.compat.create;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/**
 * Create mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it.
 *
 * <p>Create stops water passing through a water wheel's side, at the head of {@code FlowingFluid.canPassThrough}
 * ({@code create$canPassThroughOnWaterWheel}). It reads the block below the water and can only answer false. A still-water tick GA2
 * leaves out ({@code fluid.StillWater}: source water over source water, with source water on every side) asks that test with the
 * block below being water, where Create's code returns without answering. No spread follows either way.
 */
public final class CreateMixins {
    private CreateMixins() {}

    public static final String MOD_ID = "create";
    public static final String WATER_WHEEL = "com.simibubi.create.foundation.mixin.WaterWheelFluidSpreadMixin";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.FLUID_POST_PROCESS, WATER_WHEEL, Set.of("create$canPassThroughOnWaterWheel"),
            "Create's water wheels still stop water flowing through their sides. Water that cannot flow anywhere never gets that far, so GA2 still leaves its tick out."));
}
