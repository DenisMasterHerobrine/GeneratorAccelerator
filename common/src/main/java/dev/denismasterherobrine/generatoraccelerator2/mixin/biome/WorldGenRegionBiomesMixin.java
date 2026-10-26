package dev.denismasterherobrine.generatoraccelerator2.mixin.biome;

import dev.denismasterherobrine.generatoraccelerator2.biome.BiomeAnswers;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * The region's {@code getUncachedNoiseBiome}, the biome of a quart point outside the region's chunks: the level's answer, kept
 * per point where the level's biome source answers from the position alone ({@link BiomeAnswers}). Put in place once the class
 * is complete, when no other mod changed the method ({@code compat.LateOverwrites}).
 */
@Mixin(value = WorldGenRegion.class, priority = 5000)
public abstract class WorldGenRegionBiomesMixin {
    @Shadow
    @Final
    private ServerLevel level;

    /** The body of {@code getUncachedNoiseBiome(x, y, z)}. */
    @Unique
    private Holder<Biome> ga2$getUncachedNoiseBiome(int x, int y, int z) {
        BiomeAnswers answers = BiomeAnswers.of(this.level);
        return answers != null ? answers.get(x, y, z) : this.level.getUncachedNoiseBiome(x, y, z);
    }
}
