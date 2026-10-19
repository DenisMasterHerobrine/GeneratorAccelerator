package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** GPU decoration ({@code gpu.decor.DecorSpec}) reads this vanilla object's configuration. */
@Mixin(net.minecraft.world.level.levelgen.heightproviders.VeryBiasedToBottomHeight.class)
public interface VeryBiasedToBottomHeightAccessor {
    @Accessor("minInclusive")
    net.minecraft.world.level.levelgen.VerticalAnchor ga2$min();

    @Accessor("maxInclusive")
    net.minecraft.world.level.levelgen.VerticalAnchor ga2$max();

    @Accessor("inner")
    int ga2$inner();
}
