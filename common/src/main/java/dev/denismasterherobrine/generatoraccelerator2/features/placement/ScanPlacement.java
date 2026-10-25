package dev.denismasterherobrine.generatoraccelerator2.features.placement;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;

/** Added to {@code EnvironmentScanPlacement} ({@code EnvironmentScanPredicateMixin}): its scan without the stream it returns. */
public interface ScanPlacement {
    /**
     * {@code getPositions}' scan from {@code pos}, the same predicate tests in the same order. True when it hands a position on: then
     * {@code pos} is that position (vanilla hands on its own mutable position).
     */
    boolean ga2$scan(WorldGenLevel level, BlockPos.MutableBlockPos pos);
}
