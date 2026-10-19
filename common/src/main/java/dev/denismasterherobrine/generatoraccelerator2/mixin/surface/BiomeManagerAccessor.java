package dev.denismasterherobrine.generatoraccelerator2.mixin.surface;

import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BiomeManager.class)
public interface BiomeManagerAccessor {
    @Accessor("noiseBiomeSource")
    BiomeManager.NoiseBiomeSource ga2$noiseBiomeSource();

    @Accessor("biomeZoomSeed")
    long ga2$biomeZoomSeed();
}
