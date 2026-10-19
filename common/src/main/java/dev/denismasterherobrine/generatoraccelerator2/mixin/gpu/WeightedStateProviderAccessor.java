package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** GPU decoration ({@code gpu.decor.DecorSpec}) reads this vanilla object's configuration. */
@Mixin(net.minecraft.world.level.levelgen.feature.stateproviders.WeightedStateProvider.class)
public interface WeightedStateProviderAccessor {
    @Accessor("weightedList")
    net.minecraft.util.random.SimpleWeightedRandomList<net.minecraft.world.level.block.state.BlockState> ga2$list();
}
