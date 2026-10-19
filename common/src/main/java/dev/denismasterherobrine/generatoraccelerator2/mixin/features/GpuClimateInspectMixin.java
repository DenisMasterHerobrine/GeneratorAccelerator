package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds nothing. Applied late, so the mixin plugin can record other mods' changes to the biome climate that GPU decoration
 * transcribes for snow and freeze: temperature, shouldFreeze and shouldSnow (hook {@code gpuClimate}). A change keeps the
 * snow and freeze feature on the CPU.
 */
@Mixin(value = Biome.class, priority = 5000)
public abstract class GpuClimateInspectMixin {
}
