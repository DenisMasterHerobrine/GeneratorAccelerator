package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** GPU decoration ({@code gpu.decor.DecorSpec}) reads this vanilla object's configuration. */
@Mixin(net.minecraft.world.level.levelgen.placement.EnvironmentScanPlacement.class)
public interface EnvironmentScanAccessor {
    @Accessor("directionOfSearch")
    net.minecraft.core.Direction ga2$direction();

    @Accessor("targetCondition")
    net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate ga2$target();

    @Accessor("allowedSearchCondition")
    net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate ga2$allowed();

    @Accessor("maxSteps")
    int ga2$maxSteps();
}
