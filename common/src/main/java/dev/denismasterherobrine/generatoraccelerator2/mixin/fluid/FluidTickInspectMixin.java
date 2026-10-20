package dev.denismasterherobrine.generatoraccelerator2.mixin.fluid;

import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.WaterFluid;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds nothing. Applied late so the mixin plugin can record other mods' changes to the water's tick. The post-processing skip
 * relies on what the tick does (hook {@code fluids.postProcess}, {@code fluid.StillWater}).
 */
@Mixin(value = { FluidState.class, Fluid.class, FlowingFluid.class, WaterFluid.class }, priority = 5000)
public abstract class FluidTickInspectMixin {
}
