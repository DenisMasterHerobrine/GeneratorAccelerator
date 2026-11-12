package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * {@code BiomeManager.obfuscateSeed} is a SHA-256 of the world seed. Every generation region's constructor calls it, six regions
 * per chunk, most of them on the chunk scheduler's one thread. The answer depends on the seed alone, so the last seed and its
 * hash are kept. Hook {@code biomeSeed}: another mod's change to the method keeps the hash per call.
 */
@Mixin(BiomeManager.class)
public abstract class BiomeManagerSeedMixin {
    @Unique
    private static final FeatureTargets.Hook GA2_HOOK = FeatureTargets.hook("biomeSeed");
    /** The last seed and its hash. A new array per seed, never changed once published. */
    @Unique
    private static volatile long[] ga2$last;

    @WrapMethod(method = "obfuscateSeed")
    private static long ga2$lastSeed(long seed, Operation<Long> original) {
        long[] last = ga2$last;
        if (last != null && last[0] == seed) {
            return last[1];
        }
        long hash = original.call(seed);
        if (GA2_HOOK.allowed()) {
            ga2$last = new long[] { seed, hash };
        }
        return hash;
    }
}
