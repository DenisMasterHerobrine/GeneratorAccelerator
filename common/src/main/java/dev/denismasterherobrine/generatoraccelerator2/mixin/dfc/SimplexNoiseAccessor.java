package dev.denismasterherobrine.generatoraccelerator2.mixin.dfc;

import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SimplexNoise.class)
public interface SimplexNoiseAccessor {
    @Accessor("p") int[] ga2$p();
}
