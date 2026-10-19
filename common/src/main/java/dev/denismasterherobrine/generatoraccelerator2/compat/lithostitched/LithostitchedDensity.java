package dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.api.DensityFunctionLowering;
import dev.denismasterherobrine.generatoraccelerator2.compat.Reflect;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.INode;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.Node;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.ExternSamplers;
import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Lithostitched's density functions, compiled.
 *
 * <p>{@code FastNoiseDensityFunction(config, xzScale, yScale, shiftX, shiftY, shiftZ)} samples a FastNoiseLite noise
 * ({@code config.value().sample(x * xzScale + shiftX, y * yScale + shiftY, z * xzScale + shiftZ)}). Regions Unexplored
 * and Lithostitched's own region functions use it. The function's coordinate expressions and shifts lower to compiled
 * code. The noise itself stays Lithostitched's object and is called directly ({@link ExternSamplers}). The object is
 * called live, so compiled code reads the same per-world seed that Lithostitched binds into it
 * ({@code FastNoiseConfig.bind}) as vanilla's code does. Recognised by class name and record shape, with no dependency
 * on the mod. The compiler checks the lowering against the original.
 */
public final class LithostitchedDensity {
    private LithostitchedDensity() {}

    public static final String MOD_ID = "lithostitched";
    static final String FAST_NOISE = "dev.worldgen.lithostitched.impl.worldgen.densityfunction.FastNoiseDensityFunction";
    static final String CONFIG = "dev.worldgen.lithostitched.api.worldgen.densityfunction.fastnoise.FastNoiseConfig";
    private static final MethodType XYZ = MethodType.methodType(double.class, double.class, double.class, double.class);

    @SuppressWarnings("unchecked")
    public static void register() {
        Class<?> type = Reflect.type(FAST_NOISE);
        Class<?> config = Reflect.type(CONFIG);
        if (type == null || config == null || !DensityFunction.class.isAssignableFrom(type)) {
            return;
        }
        MethodHandle sample;
        try {
            sample = MethodHandles.lookup().findVirtual(config, "sample", XYZ);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Ga2Log.get("Compat").info("Lithostitched fast noise: unknown shape ({}) -> own code", e.toString());
            return;
        }
        DensityFunctionLowering.register((Class<? extends DensityFunction>) type, (fn, ctx) -> lower(fn, ctx, config, sample));
        ExternSamplers.addGpuSource(FastNoiseGpu.CL);
    }

    private static Node lower(DensityFunction fn, DensityFunctionLowering.Context ctx, Class<?> configType, MethodHandle sample) {
        Object[] c = Reflect.components(fn, FAST_NOISE, "config", "xzScale", "yScale", "shiftX", "shiftY", "shiftZ");
        if (c == null || !(c[0] instanceof Holder<?> holder) || !holder.isBound() || !(c[1] instanceof Double xzScale) || !(c[2] instanceof Double yScale)
            || !(c[3] instanceof DensityFunction shiftX) || !(c[4] instanceof DensityFunction shiftY) || !(c[5] instanceof DensityFunction shiftZ)) {
            return null;
        }
        Object config = holder.value();
        if (!configType.isInstance(config)) {
            return null;
        }
        ExternSamplers.Sampler sampler = sampler(config, configType, sample);
        if (sampler == null) {
            return null;
        }
        int index = ExternSamplers.register(config, sampler);
        // The GPU form is described again for each router, because the seed bound into the object is per world.
        ExternSamplers.registerGpu(index, FastNoiseGpu.describe(config, sampler));
        // (double) context.blockX() * xzScale + shiftX.compute(context), and so on
        Node x = axis(ctx, 0, xzScale, ctx.lower(shiftX));
        Node y = axis(ctx, 1, yScale, ctx.lower(shiftY));
        Node z = axis(ctx, 2, xzScale, ctx.lower(shiftZ));
        return ctx.intern(new Node.StaticCall(ExternSamplers.OWNER, ExternSamplers.NAME, ExternSamplers.DESC, List.of(new INode.IntConst(index), x, y, z)));
    }

    private static final long NEGATIVE_ZERO = Double.doubleToRawLongBits(-0.0);

    private static Node axis(DensityFunctionLowering.Context ctx, int axis, double scale, Node shift) {
        if (scale == 0.0 && shift instanceof Node.Const c && Double.doubleToRawLongBits(c.value()) != NEGATIVE_ZERO) {
            // coordinate * 0 is +0.0 or -0.0. Either one plus a constant other than -0.0 is that constant, bit for bit.
            // So the axis is the constant. Region and fluid functions use this as a flat y input. The analyses then see
            // no y read.
            return shift;
        }
        Node coordinate = ctx.intern(new Node.IntToDouble(new INode.IntCoord(axis)));
        Node scaled = ctx.intern(new Node.StaticCall("$op", "dmul", "(DD)D", List.of(coordinate, ctx.intern(new Node.Const(scale)))));
        return ctx.intern(new Node.StaticCall("$op", "dadd", "(DD)D", List.of(scaled, shift)));
    }

    /** A direct call of {@code config.sample(x, y, z)} through a generated lambda. Falls back to a method handle call. */
    private static ExternSamplers.Sampler sampler(Object config, Class<?> configType, MethodHandle sample) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            CallSite site = LambdaMetafactory.metafactory(lookup, "sample", MethodType.methodType(ExternSamplers.Sampler.class, configType), XYZ, sample, XYZ);
            return (ExternSamplers.Sampler) site.getTarget().invoke(config);
        } catch (Throwable t) {
            MethodHandle bound = sample.bindTo(config);
            return (x, y, z) -> {
                try {
                    return (double) bound.invokeExact(x, y, z);
                } catch (Throwable e) {
                    throw new IllegalStateException(e);
                }
            };
        }
    }
}
