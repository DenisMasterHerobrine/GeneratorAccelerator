package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** GPU decoration ({@code gpu.decor.DecorSpec}) reads this vanilla object's configuration. */
@Mixin(net.minecraft.world.level.levelgen.blockpredicates.WouldSurvivePredicate.class)
public interface WouldSurvivePredicateAccessor {
    @Accessor("offset")
    net.minecraft.core.Vec3i ga2$offset();

    @Accessor("state")
    net.minecraft.world.level.block.state.BlockState ga2$state();
}
