package dev.denismasterherobrine.generatoraccelerator2.beardifier;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Iterator;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;

/**
 * YUNG's API (every YUNG's mod) adds terrain adaptation for its own structure pieces, in two parts. First, it adds the
 * pieces' density at the end of {@code Beardifier.compute}, from two lists it keeps on the beardifier. The piece index
 * runs this step with the other mods' steps ({@link BeardExtras}). Second, those pieces fill a mask that turns water and
 * lava into other blocks at the end of {@code NoiseChunk.getInterpolatedState}. In a chunk none of YUNG's pieces reach,
 * both lists are empty and the mask stays empty. The fill then keeps its fast path there, provided YUNG's mixin is the
 * only change to the method. In a chunk with YUNG's pieces, the fill asks the NoiseChunk for every block. The lists are
 * read through YUNG's interface methods by reflection (GA2 has no dependency on YUNG's API).
 */
public final class YungPieces {
    private YungPieces() {}

    public static final String NOISE_CHUNK_MIXIN = "com.yungnickyoung.minecraft.yungsapi.mixin.NoiseChunkMixin";

    /** YUNG's two iterator getters, as {@code (Beardifier) -> Object} (each call makes a new iterator over its list). */
    private static volatile MethodHandle pieces;
    private static volatile MethodHandle junctions;
    private static volatile boolean resolved;

    /** True when the beardifier carries none of YUNG's pieces (or is not a beardifier at all). */
    public static boolean none(DensityFunctions.BeardifierOrMarker beardifier) {
        if (!(beardifier instanceof Beardifier b)) {
            return true;
        }
        if (!resolved) {
            resolve(b.getClass());
        }
        MethodHandle p = pieces;
        MethodHandle j = junctions;
        if (p == null || j == null) {
            return false;
        }
        try {
            return empty((Object) p.invokeExact(b)) && empty((Object) j.invokeExact(b));
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean empty(Object iterator) {
        return iterator == null || iterator instanceof Iterator<?> it && !it.hasNext();
    }

    private static synchronized void resolve(Class<?> beardifierClass) {
        if (resolved) {
            return;
        }
        try {
            MethodType type = MethodType.methodType(Object.class, Beardifier.class);
            pieces = MethodHandles.publicLookup().unreflect(beardifierClass.getMethod("yungsapi_getEnhancedPieceIterator")).asType(type);
            junctions = MethodHandles.publicLookup().unreflect(beardifierClass.getMethod("yungsapi_getEnhancedJunctionIterator")).asType(type);
        } catch (ReflectiveOperationException | RuntimeException e) {
            pieces = null;
            junctions = null;
        }
        resolved = true;
    }
}
