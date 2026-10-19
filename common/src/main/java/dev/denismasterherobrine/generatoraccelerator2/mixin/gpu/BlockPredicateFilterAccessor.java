package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** GPU decoration ({@code gpu.decor.DecorSpec}) reads this vanilla object's configuration. */
@Mixin(net.minecraft.world.level.levelgen.placement.BlockPredicateFilter.class)
public interface BlockPredicateFilterAccessor {
    @Accessor("predicate")
    net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate ga2$predicate();
}
