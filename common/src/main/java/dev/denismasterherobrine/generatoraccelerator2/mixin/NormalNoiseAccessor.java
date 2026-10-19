package dev.denismasterherobrine.generatoraccelerator2.mixin;

import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read access to vanilla's private fields, used only by the self-check reference. */
@Mixin(NormalNoise.class)
public interface NormalNoiseAccessor {
    @Accessor("first") PerlinNoise ga2$first();
    @Accessor("second") PerlinNoise ga2$second();
    @Accessor("valueFactor") double ga2$valueFactor();
}
