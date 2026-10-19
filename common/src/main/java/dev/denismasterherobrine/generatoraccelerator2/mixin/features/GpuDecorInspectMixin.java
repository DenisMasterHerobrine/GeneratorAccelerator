package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import net.minecraft.world.level.levelgen.feature.RandomBooleanSelectorFeature;
import net.minecraft.world.level.levelgen.feature.RandomPatchFeature;
import net.minecraft.world.level.levelgen.feature.RandomSelectorFeature;
import net.minecraft.world.level.levelgen.feature.SimpleRandomSelectorFeature;
import net.minecraft.world.level.levelgen.feature.WeightedPlacedFeature;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds nothing. Applied late, so the mixin plugin can record other mods' changes to the composite features GPU decoration places
 * itself (patches and selectors, hook {@code gpuDecorComposites}). A changed class keeps the features that use it on the CPU.
 */
@Mixin(value = { RandomPatchFeature.class, RandomSelectorFeature.class, SimpleRandomSelectorFeature.class, RandomBooleanSelectorFeature.class,
    WeightedPlacedFeature.class }, priority = 5000)
public abstract class GpuDecorInspectMixin {
}
