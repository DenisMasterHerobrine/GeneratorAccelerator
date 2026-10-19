package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** GPU decoration's trees ({@code gpu.decor.TreeSpec}) read this vanilla object's configuration. */
@Mixin(net.minecraft.world.level.levelgen.feature.featuresize.ThreeLayersFeatureSize.class)
public interface ThreeLayersFeatureSizeAccessor {
    @Accessor("limit")
    int ga2$limit();

    @Accessor("upperLimit")
    int ga2$upperLimit();

    @Accessor("lowerSize")
    int ga2$lower();

    @Accessor("middleSize")
    int ga2$middle();

    @Accessor("upperSize")
    int ga2$upper();
}
