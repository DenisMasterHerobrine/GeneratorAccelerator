package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.denismasterherobrine.generatoraccelerator2.features.FeatureHooks;
import dev.denismasterherobrine.generatoraccelerator2.features.modded.ModdedFeatures;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Some modded features share state between placements in their code ({@link ModdedFeatures}). During decoration on a
 * generation region, their placements run one at a time. Every other feature still decorates in parallel. Placements
 * outside world generation, such as a sapling growing on the server thread, are untouched. Vanilla never ordered those
 * placements against decoration either.
 */
@Mixin(value = Feature.class, priority = 5000)
public abstract class FeatureSharedStateMixin {
    @WrapMethod(method = "place(Lnet/minecraft/world/level/levelgen/feature/configurations/FeatureConfiguration;Lnet/minecraft/world/level/WorldGenLevel;"
        + "Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z")
    private boolean ga2$sharedState(FeatureConfiguration config, WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin,
                                    Operation<Boolean> original) {
        ModdedFeatures.Profile profile = level instanceof WorldGenRegion && FeatureHooks.enabled() ? ModdedFeatures.of(this.getClass()) : null;
        if (profile == null) {
            return original.call(config, level, generator, random, origin);
        }
        ModdedFeatures.placed(profile);
        if (profile.lock() == null) {
            return original.call(config, level, generator, random, origin);
        }
        // State written once from the world's seed. Runs in parallel once a locked placement has built and published it.
        if (profile.init() != null && profile.init().ready(this, level)) {
            return original.call(config, level, generator, random, origin);
        }
        synchronized (profile.lock()) {
            boolean placed = original.call(config, level, generator, random, origin);
            if (profile.init() != null) {
                profile.init().publish(this, level);
            }
            return placed;
        }
    }
}
