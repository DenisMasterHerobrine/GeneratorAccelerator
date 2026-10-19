package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** GPU decoration's trees ({@code gpu.decor.TreeSpec}) read this vanilla object's configuration. */
@Mixin(net.minecraft.world.level.levelgen.feature.treedecorators.BeehiveDecorator.class)
public interface BeehiveDecoratorAccessor {
    @Accessor("probability")
    float ga2$probability();
}
