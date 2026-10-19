package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import net.minecraft.world.level.levelgen.feature.AbstractHugeMushroomFeature;
import net.minecraft.world.level.levelgen.feature.HugeBrownMushroomFeature;
import net.minecraft.world.level.levelgen.feature.HugeRedMushroomFeature;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds nothing. Applied late, so the mixin plugin can record other mods' changes to the huge mushrooms GPU decoration transcribes
 * (hook {@code gpuHugeMushroom}). A change keeps the huge mushrooms on the CPU.
 */
@Mixin(value = { AbstractHugeMushroomFeature.class, HugeRedMushroomFeature.class, HugeBrownMushroomFeature.class }, priority = 5000)
public abstract class GpuHugeMushroomInspectMixin {
}
