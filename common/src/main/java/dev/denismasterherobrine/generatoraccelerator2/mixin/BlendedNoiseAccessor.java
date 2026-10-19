package dev.denismasterherobrine.generatoraccelerator2.mixin;

import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read access to vanilla's private fields, used only by the self-check reference. */
@Mixin(BlendedNoise.class)
public interface BlendedNoiseAccessor {
    @Accessor("minLimitNoise") PerlinNoise ga2$minLimitNoise();
    @Accessor("maxLimitNoise") PerlinNoise ga2$maxLimitNoise();
    @Accessor("mainNoise") PerlinNoise ga2$mainNoise();
    @Accessor("xzMultiplier") double ga2$xzMultiplier();
    @Accessor("yMultiplier") double ga2$yMultiplier();
    @Accessor("xzFactor") double ga2$xzFactor();
    @Accessor("yFactor") double ga2$yFactor();
    @Accessor("smearScaleMultiplier") double ga2$smearScaleMultiplier();
}
