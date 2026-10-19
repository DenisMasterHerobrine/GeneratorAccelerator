package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds nothing. Applied late so the mixin plugin can record other mods' changes to the climate sample that the GPU computes itself
 * ({@code gpu.biomes}).
 */
@Mixin(value = Climate.Sampler.class, priority = 5000)
public abstract class GpuClimateSamplerInspectMixin {
}
