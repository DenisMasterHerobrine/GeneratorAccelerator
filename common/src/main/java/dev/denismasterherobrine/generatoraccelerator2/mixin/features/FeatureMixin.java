package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import dev.denismasterherobrine.generatoraccelerator2.api.FeatureFastPath;
import dev.denismasterherobrine.generatoraccelerator2.features.FeatureHooks;
import dev.denismasterherobrine.generatoraccelerator2.features.FeatureStats;
import dev.denismasterherobrine.generatoraccelerator2.features.region.Ga2Region;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dispatches fast paths registered through the public API. Runs after vanilla's write-radius check
 * on the origin, exactly where {@code Feature.place(config, level, generator, random, origin)} would
 * build the context and call the feature.
 */
@Mixin(value = Feature.class, priority = 5000)
public abstract class FeatureMixin {
    @Inject(method = "place(Lnet/minecraft/world/level/levelgen/feature/configurations/FeatureConfiguration;Lnet/minecraft/world/level/WorldGenLevel;"
        + "Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z",
        at = @At("HEAD"), cancellable = true)
    private void ga2$registeredPlace(FeatureConfiguration config, WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin,
                                     CallbackInfoReturnable<Boolean> cir) {
        FeatureFastPath path = FeatureFastPath.lookup(this.getClass());
        if (path == null || !(level instanceof Ga2Region region) || !region.ga2$plain() || !FeatureHooks.enabled()) {
            return;
        }
        if (!level.ensureCanWrite(origin)) {
            cir.setReturnValue(false);
            return;
        }
        @SuppressWarnings({ "unchecked", "rawtypes" })
        FeaturePlaceContext<?> ctx = new FeaturePlaceContext(java.util.Optional.empty(), level, generator, random, origin, config);
        Object result = path.place(ctx, region);
        if (result == FeatureFastPath.FALLBACK) {
            FeatureStats.fallback(this.getClass());
            // Vanilla continues. It repeats the write-radius check, which is pure, and builds its own context.
            return;
        }
        FeatureStats.fast(this.getClass());
        cir.setReturnValue((Boolean) result);
    }
}
