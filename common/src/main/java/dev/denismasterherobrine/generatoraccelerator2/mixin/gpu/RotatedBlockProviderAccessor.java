package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** GPU decoration ({@code gpu.decor.DecorSpec}) reads this vanilla object's configuration. */
@Mixin(net.minecraft.world.level.levelgen.feature.stateproviders.RotatedBlockProvider.class)
public interface RotatedBlockProviderAccessor {
    @Accessor("block")
    net.minecraft.world.level.block.Block ga2$block();
}
