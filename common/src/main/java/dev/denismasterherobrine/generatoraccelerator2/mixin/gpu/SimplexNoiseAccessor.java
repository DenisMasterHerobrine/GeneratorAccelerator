package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A simplex noise's permutation table, for the GPU's copy of the temperature noises. */
@Mixin(SimplexNoise.class)
public interface SimplexNoiseAccessor {
    @Accessor("p")
    int[] ga2$p();
}
