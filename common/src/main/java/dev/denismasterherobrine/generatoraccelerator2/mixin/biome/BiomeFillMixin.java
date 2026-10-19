package dev.denismasterherobrine.generatoraccelerator2.mixin.biome;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.denismasterherobrine.generatoraccelerator2.biome.FlatClimateTree;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Marks the thread while a chunk fills its biomes. The climate searches make their per-column answers only during the fill, when
 * each quart column is asked once per quart layer ({@link FlatClimateTree#enterFill}). The fill itself runs unchanged.
 */
@Mixin(ChunkAccess.class)
public abstract class BiomeFillMixin {
    @WrapMethod(method = "fillBiomesFromNoise(Lnet/minecraft/world/level/biome/BiomeResolver;Lnet/minecraft/world/level/biome/Climate$Sampler;)V")
    private void ga2$markBiomeFill(BiomeResolver resolver, Climate.Sampler sampler, Operation<Void> original) {
        FlatClimateTree.enterFill();
        try {
            original.call(resolver, sampler);
        } finally {
            FlatClimateTree.exitFill();
            dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.BoundSampler.release(sampler);
        }
    }
}
