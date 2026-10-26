package dev.denismasterherobrine.generatoraccelerator2.mixin.biome;

import dev.denismasterherobrine.generatoraccelerator2.biome.BiomeAnswers;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Keeps the level's biome answers ({@link BiomeAnswers}). Applied late so the mixin plugin can record other mods' changes to
 * {@code getUncachedNoiseBiome}, whose answers are kept ({@code biomes.regionAnswers}).
 */
@Mixin(value = ServerLevel.class, priority = 5000)
public abstract class ServerLevelBiomeAnswersMixin implements BiomeAnswers.Kept {
    @Unique
    private volatile BiomeAnswers ga2$biomeAnswers;

    @Override
    public BiomeAnswers ga2$biomeAnswers() {
        return this.ga2$biomeAnswers;
    }

    @Override
    public void ga2$setBiomeAnswers(BiomeAnswers answers) {
        this.ga2$biomeAnswers = answers;
    }
}
