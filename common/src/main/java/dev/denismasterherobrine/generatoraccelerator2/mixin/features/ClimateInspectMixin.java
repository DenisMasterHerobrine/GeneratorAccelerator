package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The biome's climate, for the snow and freeze fast path's warm test ({@code features.fast.WarmBiomes}). Applied late, so the mixin
 * plugin can record other mods' changes to the climate methods (hook {@code climate}). After such a change every column takes
 * vanilla's test.
 */
@Mixin(value = Biome.class, priority = 5000)
public interface ClimateInspectMixin {
    @Accessor("climateSettings")
    Biome.ClimateSettings ga2$climateSettings();
}
