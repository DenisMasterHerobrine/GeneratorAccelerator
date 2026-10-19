package dev.denismasterherobrine.generatoraccelerator2.compat.vanillabackport;

import dev.denismasterherobrine.generatoraccelerator2.compat.Reflect;
import dev.denismasterherobrine.generatoraccelerator2.surface.compile.Ir;
import dev.denismasterherobrine.generatoraccelerator2.surface.compile.SurfaceEval;
import dev.denismasterherobrine.generatoraccelerator2.surface.compile.SurfaceExtern;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Vanilla Backport's surface condition {@code SpatialNoiseThresholdConditionSource(noise, minThreshold, maxThreshold)}. It tests whether
 * the condition's noise at the block's x, y and z lies within the thresholds, both included. The sampler that Vanilla Backport's context
 * mixin hands out recomputes the noise whenever the block's y changes, so once per block. Compiled as an extern condition with a GPU
 * form, instead of running as an opaque rule object. Recognised by class name and fields, with no dependency on the mod.
 */
public final class VanillaBackportRules {
    private VanillaBackportRules() {}

    public static final String MOD_ID = "vanillabackport";
    static final String SPATIAL = "com.blackgear.vanillabackport.common.worldgen.surface.SpatialNoiseThresholdConditionSource";

    public static void register() {
        SurfaceExtern.register((SurfaceExtern.CondLowering) VanillaBackportRules::lower);
    }

    static Ir.Cond lower(SurfaceRules.ConditionSource source, SurfaceExtern.Context ctx) {
        if (!source.getClass().getName().equals(SPATIAL)) {
            return null;
        }
        Object noise = Reflect.field(source, SPATIAL, "noise");
        Object min = Reflect.field(source, SPATIAL, "minThreshold");
        Object max = Reflect.field(source, SPATIAL, "maxThreshold");
        if (!(noise instanceof ResourceKey<?> key) || !(min instanceof Double lo) || !(max instanceof Double hi)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        ResourceKey<NormalNoise.NoiseParameters> k = (ResourceKey<NormalNoise.NoiseParameters>) key;
        return ctx.extern(new Spatial(k, lo, hi));
    }

    private static final class Spatial implements SurfaceExtern.Cond, SurfaceExtern.Gpu {
        private final ResourceKey<NormalNoise.NoiseParameters> key;
        private final double min, max;
        /** The noise of the last random state asked, as {random state, noise}. Nearly always one world's. */
        private volatile Object[] last;

        Spatial(ResourceKey<NormalNoise.NoiseParameters> key, double min, double max) {
            this.key = key;
            this.min = min;
            this.max = max;
        }

        private NormalNoise noise(RandomState randomState) {
            Object[] l = last;
            if (l != null && l[0] == randomState) {
                return (NormalNoise) l[1];
            }
            NormalNoise n = randomState.getOrCreateNoise(key);
            last = new Object[] { randomState, n };
            return n;
        }

        @Override
        public boolean test(SurfaceEval s) {
            double v = noise(s.randomState()).getValue(s.blockX, s.blockY, s.blockZ);
            return v >= min && v <= max;
        }

        @Override
        public Ir.Scope scope() {
            return Ir.Scope.BLOCK;
        }

        @Override
        public boolean positional() {
            return true;
        }

        @Override
        public String gpu(SurfaceExtern.GpuSetup setup) {
            String v = setup.noise(setup.randomState().getOrCreateNoise(key), "(double) s->x", "(double) s->y", "(double) s->z");
            return "sf_between(" + v + ", " + literal(min) + ", " + literal(max) + ")";
        }

        /** An OpenCL double literal of exactly {@code d}. */
        private static String literal(double d) {
            if (Double.isInfinite(d)) {
                return d > 0 ? "INFINITY" : "(-INFINITY)";
            }
            return "(" + Double.toHexString(d) + ")";
        }
    }
}
