package dev.denismasterherobrine.generatoraccelerator2.dfc.runtime;

import java.util.function.Function;
import javax.annotation.Nullable;
import net.minecraft.world.level.biome.Climate;

/**
 * Samplers that other mods build around a bound sampler's own functions. {@code compat/<modid>} sets {@link #resolver}.
 * For a sampler without a binding, the resolver finds the binding that samples the same values, or returns null.
 */
public final class SamplerWrappers {
    private SamplerWrappers() {}

    @Nullable
    public static volatile Function<Climate.Sampler, BoundSampler> resolver;

    @Nullable
    public static BoundSampler resolve(Climate.Sampler sampler) {
        BoundSampler bound = BoundSampler.lookup(sampler);
        if (bound == null) {
            Function<Climate.Sampler, BoundSampler> r = resolver;
            if (r != null) {
                bound = r.apply(sampler);
            }
        }
        return bound;
    }
}
