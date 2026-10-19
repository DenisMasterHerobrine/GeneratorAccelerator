package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** GPU decoration's trees ({@code gpu.decor.TreeSpec}) read this vanilla object's configuration. */
@Mixin(net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacer.class)
public interface TrunkPlacerAccessor {
    @Accessor("baseHeight")
    int ga2$base();

    @Accessor("heightRandA")
    int ga2$randA();

    @Accessor("heightRandB")
    int ga2$randB();
}
