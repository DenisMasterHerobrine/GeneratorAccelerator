package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code PlacementFilter.shouldPlace}. Every filter, vanilla's or a mod's, has vanilla's final {@code getPositions}, which
 * is {@code shouldPlace(...) ? Stream.of(pos) : Stream.of()}. The placement pipeline therefore calls {@code shouldPlace} directly.
 */
@Mixin(PlacementFilter.class)
public interface PlacementFilterInvoker {
    @Invoker("shouldPlace")
    boolean ga2$shouldPlace(PlacementContext context, RandomSource random, BlockPos pos);
}
