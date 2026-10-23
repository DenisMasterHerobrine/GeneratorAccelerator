package dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.lang.reflect.Method;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;

/**
 * The GPU's biomes under Lithostitched's biome injectors. Lithostitched wraps the dimension's biome source in its injector source
 * once a mod adds injectors. For every quart the injector source samples the climate point, asks its base source for the biome,
 * then runs the injectors on that biome. The GPU computes the base source's answers and the climate points. The injector source's
 * own code then runs the injectors, with the GPU's point and base biome handed to its two lookups
 * ({@code compat.lithostitched.mixin.InjectorBiomeSourceGpuMixin}). Everything else in it, the regions and the injectors' own
 * density functions included, runs as before.
 *
 * <p>The base source must be the vanilla multi-noise source the injector source wraps directly. The GPU searches that source's
 * trees, TerraBlender's regions included ({@code gpu.runtime.GpuWorldgen}). Lithostitched's classes are reached by name.
 */
public final class LithostitchedGpuBiomes {
    private LithostitchedGpuBiomes() {}

    static final String INJECTOR_SOURCE = "dev.worldgen.lithostitched.impl.worldgen.biomeinjector.internal.InjectorBiomeSource";

    /** The point and base biome the next lookups of the injector source on this thread take, and whether they were taken. */
    private static final class Pending {
        Climate.TargetPoint point;
        Holder<Biome> base;
        boolean pointTaken, baseTaken;
        /** The point the injector source sampled last, and where. Its base lookup samples the same point again ({@link #reused}). */
        Climate.Sampler sampled;
        int sx, sy, sz;
        Climate.TargetPoint sampledPoint;
        /** Inside the base lookup that follows the sample. */
        boolean inBase;
    }

    /** Set once an injector source sampled through {@link #point}. Before that no thread has anything to reuse. */
    private static volatile boolean sampling;
    /** Climate samples the base lookup took from the injector source's own sample. */
    public static final java.util.concurrent.atomic.LongAdder REUSED_POINTS = new java.util.concurrent.atomic.LongAdder();

    private static final ThreadLocal<Pending> PENDING = ThreadLocal.withInitial(Pending::new);

    /** Climate points the injector source took from the GPU instead of sampling them. */
    public static final java.util.concurrent.atomic.LongAdder HANDED_POINTS = new java.util.concurrent.atomic.LongAdder();

    /** Whether {@code source} is Lithostitched's injector source. */
    public static boolean isInjector(@Nullable BiomeSource source) {
        return source != null && source.getClass().getName().equals(INJECTOR_SOURCE);
    }

    /**
     * The multi-noise source an injector source wraps directly, or null when it wraps anything else. Lithostitched also keeps the
     * innermost source ({@code rootDelegate}). The two must be the same vanilla source.
     */
    @Nullable
    public static MultiNoiseBiomeSource base(BiomeSource injector) {
        try {
            Method direct = injector.getClass().getMethod("directDelegate");
            Method root = injector.getClass().getMethod("rootDelegate");
            Object d = direct.invoke(injector), r = root.invoke(injector);
            return d == r && d != null && d.getClass() == MultiNoiseBiomeSource.class ? (MultiNoiseBiomeSource) d : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** The biome {@code injector} gives at quart {@code x, y, z} when its climate point is {@code point} and its base biome {@code base}. */
    public static Holder<Biome> inject(BiomeSource injector, int x, int y, int z, @Nullable Climate.Sampler sampler, Climate.TargetPoint point, Holder<Biome> base) {
        Pending p = PENDING.get();
        p.point = point;
        p.base = base;
        try {
            return injector.getNoiseBiome(x, y, z, sampler);
        } finally {
            p.point = null;
            p.base = null;
        }
    }

    /**
     * Whether the injector source takes a handed point and base biome (GA2's mixin applied to this Lithostitched version). Asks
     * it once, at the origin, with the dimension's own sampler.
     */
    public static boolean takesHandedLookups(BiomeSource injector, Climate.Sampler sampler, Holder<Biome> anyBiome) {
        Pending p = PENDING.get();
        p.pointTaken = p.baseTaken = false;
        try {
            inject(injector, 0, 0, 0, sampler, sampler.sample(0, 0, 0), anyBiome);
        } catch (RuntimeException e) {
            return false;
        }
        // the point is not read when the source has no injectors at all. The base biome always is
        return p.baseTaken;
    }

    /** The injector source's {@code sampler.sample(x, y, z)}: the handed point, or the sampler's. */
    public static Climate.TargetPoint point(Climate.Sampler sampler, int x, int y, int z, Operation<Climate.TargetPoint> original) {
        Pending p = PENDING.get();
        if (p.point != null) {
            p.pointTaken = true;
            HANDED_POINTS.increment();
            return p.point;
        }
        Climate.TargetPoint sampled = original.call(sampler, x, y, z);
        p.sampled = sampler;
        p.sx = x;
        p.sy = y;
        p.sz = z;
        p.sampledPoint = sampled;
        if (!sampling) {
            sampling = true;
        }
        return sampled;
    }

    /** The injector source's {@code baseResolver.getNoiseBiome(x, y, z, sampler)}: the handed base biome, or the resolver's. */
    public static Holder<Biome> base(BiomeResolver resolver, int x, int y, int z, Climate.Sampler sampler, Operation<Holder<Biome>> original) {
        Pending p = PENDING.get();
        if (p.base != null) {
            p.baseTaken = true;
            return p.base;
        }
        // The base source samples the point the injector source just sampled, at the same quart with the same sampler.
        boolean outer = !p.inBase;
        p.inBase = true;
        try {
            return original.call(resolver, x, y, z, sampler);
        } finally {
            if (outer) {
                p.inBase = false;
                p.sampled = null;
                p.sampledPoint = null;
            }
        }
    }

    /**
     * The point {@code sampler} gives at quart {@code x, y, z} when the injector source sampled exactly that point just before its
     * base lookup, or null. Sampling is a function of the sampler and the quart, so the base source gets the same values without
     * computing all six climate parameters again.
     */
    @Nullable
    public static Climate.TargetPoint reused(Climate.Sampler sampler, int x, int y, int z) {
        if (!sampling) {
            return null;
        }
        Pending p = PENDING.get();
        if (p.inBase && p.sampled == sampler && p.sx == x && p.sy == y && p.sz == z) {
            REUSED_POINTS.increment();
            return p.sampledPoint;
        }
        return null;
    }
}
