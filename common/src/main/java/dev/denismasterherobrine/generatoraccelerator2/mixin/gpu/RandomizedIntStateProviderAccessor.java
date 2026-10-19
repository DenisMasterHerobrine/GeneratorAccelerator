package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** GPU decoration ({@code gpu.decor.DecorSpec}) reads this vanilla object's configuration. */
@Mixin(net.minecraft.world.level.levelgen.feature.stateproviders.RandomizedIntStateProvider.class)
public interface RandomizedIntStateProviderAccessor {
    @Accessor("source")
    net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider ga2$source();

    @Accessor("propertyName")
    String ga2$propertyName();

    @Accessor("values")
    net.minecraft.util.valueproviders.IntProvider ga2$values();
}
