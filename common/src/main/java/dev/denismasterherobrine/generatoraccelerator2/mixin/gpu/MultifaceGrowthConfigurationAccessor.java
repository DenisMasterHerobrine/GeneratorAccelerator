package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** GPU decoration ({@code gpu.decor.DecorSpec}) reads this vanilla object's configuration. */
@Mixin(net.minecraft.world.level.levelgen.feature.configurations.MultifaceGrowthConfiguration.class)
public interface MultifaceGrowthConfigurationAccessor {
    @Accessor("validDirections")
    it.unimi.dsi.fastutil.objects.ObjectArrayList<net.minecraft.core.Direction> ga2$validDirections();
}
