package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.RepeatingPlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code RepeatingPlacement.count}. Vanilla's {@code getPositions} repeats the position {@code count(...)} times. For a
 * subclass that keeps that {@code getPositions}, the placement pipeline calls {@code count} directly.
 */
@Mixin(RepeatingPlacement.class)
public interface RepeatingPlacementInvoker {
    @Invoker("count")
    int ga2$count(RandomSource random, BlockPos pos);
}
