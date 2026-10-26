package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.BlockPredicateFilter;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.EnvironmentScanPlacement;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.HeightmapPlacement;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.NoiseBasedCountPlacement;
import net.minecraft.world.level.levelgen.placement.NoiseThresholdCountPlacement;
import net.minecraft.world.level.levelgen.placement.RandomOffsetPlacement;
import net.minecraft.world.level.levelgen.placement.RarityFilter;
import net.minecraft.world.level.levelgen.placement.SurfaceRelativeThresholdFilter;
import net.minecraft.world.level.levelgen.placement.SurfaceWaterDepthFilter;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds nothing. Applied late so the mixin plugin can record, per class, other mods' changes to the placement modifiers that
 * the placement pipeline transcribes ({@code placement.<class>}). A changed class goes through its own methods instead.
 */
@Mixin(value = { CountPlacement.class, InSquarePlacement.class, HeightmapPlacement.class, HeightRangePlacement.class, BiomeFilter.class,
    RarityFilter.class, BlockPredicateFilter.class, SurfaceWaterDepthFilter.class, SurfaceRelativeThresholdFilter.class,
    NoiseThresholdCountPlacement.class, NoiseBasedCountPlacement.class, RandomOffsetPlacement.class,
    EnvironmentScanPlacement.class }, priority = 5000)
public abstract class PlacementModifiersInspectMixin {
}
