package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The biome source's parameter list, whose climate tree the GPU searches. Applied late so the mixin plugin can record other mods'
 * changes to the biome lookup that the GPU replaces ({@code gpu.biomes}).
 */
@Mixin(value = MultiNoiseBiomeSource.class, priority = 5000)
public interface GpuBiomeSourceInspectMixin {
    @Invoker("parameters")
    Climate.ParameterList<Holder<Biome>> ga2$gpuParameters();
}
