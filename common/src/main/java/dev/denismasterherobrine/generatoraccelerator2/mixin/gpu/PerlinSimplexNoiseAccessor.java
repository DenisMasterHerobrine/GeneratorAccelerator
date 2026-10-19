package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import net.minecraft.world.level.levelgen.synth.PerlinSimplexNoise;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A layered simplex noise's octaves and factors, for the GPU's copy of the temperature noises. */
@Mixin(PerlinSimplexNoise.class)
public interface PerlinSimplexNoiseAccessor {
    @Accessor("noiseLevels")
    SimplexNoise[] ga2$noiseLevels();

    @Accessor("highestFreqInputFactor")
    double ga2$highestFreqInputFactor();

    @Accessor("highestFreqValueFactor")
    double ga2$highestFreqValueFactor();
}
