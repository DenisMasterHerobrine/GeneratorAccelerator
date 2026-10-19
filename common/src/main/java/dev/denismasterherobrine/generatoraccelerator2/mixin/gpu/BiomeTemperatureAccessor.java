package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.synth.PerlinSimplexNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The noises {@code Biome.getTemperature} reads, for the GPU's surface rules ({@code gpu.surface}). Applied late, so the mixin plugin
 * can record other mods' changes to the temperature methods ({@code gpu.surface}).
 */
@Mixin(value = Biome.class, priority = 5000)
public interface BiomeTemperatureAccessor {
    @Accessor("TEMPERATURE_NOISE")
    static PerlinSimplexNoise ga2$temperatureNoise() {
        throw new AssertionError();
    }

    @Accessor("FROZEN_TEMPERATURE_NOISE")
    static PerlinSimplexNoise ga2$frozenTemperatureNoise() {
        throw new AssertionError();
    }
}
