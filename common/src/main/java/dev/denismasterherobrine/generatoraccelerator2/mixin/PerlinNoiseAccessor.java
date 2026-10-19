package dev.denismasterherobrine.generatoraccelerator2.mixin;

import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read access to vanilla's private octave data, used only by the self-check reference. */
@Mixin(PerlinNoise.class)
public interface PerlinNoiseAccessor {
    @Accessor("noiseLevels")
    ImprovedNoise[] ga2$noiseLevels();

    @Accessor("amplitudes")
    DoubleList ga2$amplitudes();

    @Accessor("lowestFreqInputFactor")
    double ga2$lowestFreqInputFactor();

    @Accessor("lowestFreqValueFactor")
    double ga2$lowestFreqValueFactor();
}
