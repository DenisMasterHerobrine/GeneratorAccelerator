package dev.denismasterherobrine.generatoraccelerator2.beardifier;

import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunctions;

/**
 * YUNG's API (every YUNG's mod) adds terrain adaptation for its own structure pieces, in two parts. First, it adds the
 * pieces' density at the end of {@code Beardifier.compute}, from two lists it keeps on the beardifier. The piece index
 * runs this step with the other mods' steps ({@link BeardExtras}). Second, those pieces fill a mask that turns water and
 * lava into other blocks at the end of {@code NoiseChunk.getInterpolatedState}. In a chunk none of YUNG's pieces reach,
 * both lists are empty and the mask stays empty. The fill then keeps its fast path there, provided YUNG's mixin is the
 * only change to the method. In a chunk with YUNG's pieces, the fill asks the NoiseChunk for every block, or runs YUNG's
 * fluid step itself ({@link #fillState}). The lists are read through YUNG's interface methods by reflection (GA2 has no
 * dependency on YUNG's API).
 */
public final class YungPieces {
    private YungPieces() {}

    public static final String NOISE_CHUNK_MIXIN = "com.yungnickyoung.minecraft.yungsapi.mixin.NoiseChunkMixin";

    /** YUNG's two iterator getters, as {@code (Beardifier) -> Object} (each call makes a new iterator over its list). */
    private static volatile java.lang.invoke.MethodHandle pieces;
    private static volatile java.lang.invoke.MethodHandle junctions;
    private static volatile boolean resolved;

    /** True when {@code foreign} (the changes to some methods) is exactly YUNG's {@code mixin}. */
    public static boolean onlyYung(List<String> foreign, String mixin) {
        return foreign.size() == 1 && foreign.get(0).equals(mixin);
    }

    /** True when the beardifier carries none of YUNG's pieces (or is not a beardifier at all). */
    public static boolean none(DensityFunctions.BeardifierOrMarker beardifier) {
        if (!(beardifier instanceof Beardifier b)) {
            return true;
        }
        if (!resolved) {
            resolve(b.getClass());
        }
        java.lang.invoke.MethodHandle p = pieces;
        java.lang.invoke.MethodHandle j = junctions;
        if (p == null || j == null) {
            return false;
        }
        try {
            return empty((Object) p.invokeExact(b)) && empty((Object) j.invokeExact(b));
        } catch (Throwable t) {
            return false;
        }
    }

    private static volatile java.lang.invoke.MethodHandle fillState;
    private static volatile boolean fillStateResolved;

    /**
     * True when YUNG's fluid step of {@code NoiseChunk.getInterpolatedState} can be called. That step is the
     * {@code yungsapi_dontFillMarkedPositions} handler. It turns water and lava that YUNG's pieces marked into another
     * block. When the step is callable, FastFill can compute the state itself in chunks with YUNG's pieces.
     */
    public static boolean fillStateReady() {
        if (!fillStateResolved) {
            synchronized (YungPieces.class) {
                if (!fillStateResolved) {
                    java.lang.invoke.MethodHandle h = null;
                    for (Method m : dev.denismasterherobrine.generatoraccelerator2.compat.Reflect.declaredMethods(net.minecraft.world.level.levelgen.NoiseChunk.class)) {
                        if (m.getName().endsWith("$yungsapi_dontFillMarkedPositions") && m.getReturnType() == void.class
                            && java.util.Arrays.equals(m.getParameterTypes(), new Class<?>[] {org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable.class})) {
                            try {
                                m.setAccessible(true);
                                h = java.lang.invoke.MethodHandles.lookup().unreflect(m);
                            } catch (ReflectiveOperationException | RuntimeException e) {
                                h = null;
                            }
                        }
                    }
                    fillState = h;
                    fillStateResolved = true;
                }
            }
        }
        return fillState != null;
    }

    /**
     * {@code state} after YUNG's fluid step, at the NoiseChunk's current block. Only water and lava can change. YUNG's
     * step leaves every other state alone, so FastFill asks only for water and lava.
     */
    @SuppressWarnings("unchecked")
    public static net.minecraft.world.level.block.state.BlockState fillState(net.minecraft.world.level.levelgen.NoiseChunk noiseChunk,
                                                                             net.minecraft.world.level.block.state.BlockState state) {
        org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<net.minecraft.world.level.block.state.BlockState> cir =
            new org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<>("getInterpolatedState", true, state);
        try {
            fillState.invoke(noiseChunk, cir);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
        return cir.getReturnValue();
    }

    private static boolean empty(Object iterator) {
        return iterator == null || iterator instanceof Iterator<?> it && !it.hasNext();
    }

    private static synchronized void resolve(Class<?> beardifierClass) {
        if (resolved) {
            return;
        }
        try {
            java.lang.invoke.MethodType type = java.lang.invoke.MethodType.methodType(Object.class, Beardifier.class);
            pieces = java.lang.invoke.MethodHandles.publicLookup().unreflect(beardifierClass.getMethod("yungsapi_getEnhancedPieceIterator")).asType(type);
            junctions = java.lang.invoke.MethodHandles.publicLookup().unreflect(beardifierClass.getMethod("yungsapi_getEnhancedJunctionIterator")).asType(type);
        } catch (ReflectiveOperationException | RuntimeException e) {
            pieces = null;
            junctions = null;
        }
        resolved = true;
    }
}
