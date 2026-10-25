package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import java.util.List;
import java.util.function.Supplier;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The placed features of each decoration step in their sorted order ({@code applyBiomeDecoration} reads the same). */
@Mixin(ChunkGenerator.class)
public interface ChunkGeneratorFeaturesAccessor {
    @Accessor("featuresPerStep")
    Supplier<List<FeatureSorter.StepFeatureData>> ga2$featuresPerStep();
}
