package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** GPU decoration ({@code gpu.decor.DecorSpec}) reads this vanilla object's configuration. */
@Mixin(net.minecraft.util.valueproviders.ClampedInt.class)
public interface ClampedIntAccessor {
    @Accessor("source")
    net.minecraft.util.valueproviders.IntProvider ga2$source();

    @Accessor("minInclusive")
    int ga2$min();

    @Accessor("maxInclusive")
    int ga2$max();
}
