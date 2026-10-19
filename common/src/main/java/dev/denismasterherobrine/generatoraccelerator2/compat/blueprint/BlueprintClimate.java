package dev.denismasterherobrine.generatoraccelerator2.compat.blueprint;

import com.google.common.collect.MapMaker;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.BoundSampler;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.SamplerWrappers;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Blueprint's modded biome slices ({@code ModdedBiomeSource}) sample the climate again for their own providers. Atmospheric
 * and the other Team Abnormals mods use these slices. The slices sample through a sampler that Blueprint builds per thread.
 * That sampler wraps per-point caches ({@code SinglePointCacheDensityFunction}) around the chunk sampler's own six
 * functions. A cache returns its function's value at the requested point, so Blueprint's sampler samples exactly what the
 * chunk sampler samples. GA2 therefore serves Blueprint's sampler with the chunk sampler's compiled program
 * ({@code BoundSampler}). GA2 also serves Blueprint's {@code getTargetPoint} ({@code mixin/ScopedContextMixin}). It
 * returns the same six values at the same block position, rounded to float as {@code Climate.Sampler.sample} does.
 * Without this, every slice choice evaluated the six functions in interpreted form. That was a quarter of the biome step in
 * a 76-mod pack.
 *
 * <p>GA2 reads the wrapped functions through the cache class's {@code base()} accessor by reflection, because GA2 has no
 * dependency on Blueprint. {@code -Dgeneratoraccelerator2.blueprint.climate=false} turns this off.
 */
public final class BlueprintClimate {
    private BlueprintClimate() {}

    public static final String MOD_ID = "blueprint";
    public static final boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.blueprint.climate"));
    private static final String CACHE = "com.teamabnormals.blueprint.core.registry.BlueprintDensityFunctions$SinglePointCacheDensityFunction";
    /** Blueprint samplers served by a binding. Each sampler is counted once. */
    public static final LongAdder ALIASED = new LongAdder();
    /** {@code -Dgeneratoraccelerator2.verify.blueprint=N}: every N-th target point GA2 serves is also computed Blueprint's way. */
    private static final int VERIFY_EVERY = Integer.getInteger("generatoraccelerator2.verify.blueprint", 0);
    private static final java.util.concurrent.atomic.AtomicLong VERIFY_COUNTER = new java.util.concurrent.atomic.AtomicLong();
    public static final LongAdder VERIFIED = new LongAdder();
    public static final LongAdder DIFFERING = new LongAdder();

    public static boolean verifyNow() {
        return VERIFY_EVERY > 0 && VERIFY_COUNTER.incrementAndGet() % VERIFY_EVERY == 0;
    }

    public static void verified(boolean same) {
        VERIFIED.increment();
        if (!same) {
            DIFFERING.increment();
        }
    }

    @Nullable
    private static volatile Class<?> cacheClass;
    @Nullable
    private static volatile MethodHandle base;
    /** Samplers that are not Blueprint's or match no binding. Recording them makes the reflection run once per sampler. */
    private static final ConcurrentMap<Climate.Sampler, Boolean> REJECTED = new MapMaker().weakKeys().concurrencyLevel(8).makeMap();

    public static void register() {
        if (!ENABLED) {
            return;
        }
        try {
            Class<?> c = Class.forName(CACHE, false, BlueprintClimate.class.getClassLoader());
            base = MethodHandles.publicLookup().findVirtual(c, "base", MethodType.methodType(DensityFunction.class))
                .asType(MethodType.methodType(DensityFunction.class, DensityFunction.class));
            cacheClass = c;
            SamplerWrappers.resolver = BlueprintClimate::resolve;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            cacheClass = null;
        }
    }

    /** If {@code sampler} is Blueprint's per-point sampler, the binding that samples the same values. Otherwise null. */
    @Nullable
    public static BoundSampler resolve(Climate.Sampler sampler) {
        Class<?> c = cacheClass;
        if (c == null || REJECTED.containsKey(sampler)) {
            return null;
        }
        if (sampler.temperature().getClass() != c || sampler.humidity().getClass() != c || sampler.continentalness().getClass() != c
            || sampler.erosion().getClass() != c || sampler.depth().getClass() != c || sampler.weirdness().getClass() != c) {
            REJECTED.put(sampler, Boolean.TRUE);
            return null;
        }
        BoundSampler bound = BoundSampler.forFunctions(base(sampler.temperature()), base(sampler.humidity()), base(sampler.continentalness()),
            base(sampler.erosion()), base(sampler.depth()), base(sampler.weirdness()));
        if (bound == null) {
            REJECTED.put(sampler, Boolean.TRUE);
            return null;
        }
        BoundSampler.alias(sampler, bound);
        ALIASED.increment();
        return bound;
    }

    private static DensityFunction base(DensityFunction cache) {
        try {
            return (DensityFunction) base.invokeExact(cache);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }
}
