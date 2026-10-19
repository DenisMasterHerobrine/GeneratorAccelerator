package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** GPU decoration ({@code gpu.decor.DecorSpec}) reads this vanilla object's configuration. */
@Mixin(net.minecraft.world.level.levelgen.feature.stateproviders.SimpleStateProvider.class)
public interface SimpleStateProviderAccessor {
    @Accessor("state")
    net.minecraft.world.level.block.state.BlockState ga2$state();
}
