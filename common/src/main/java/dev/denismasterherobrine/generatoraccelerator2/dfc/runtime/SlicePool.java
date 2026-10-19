package dev.denismasterherobrine.generatoraccelerator2.dfc.runtime;

import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * Interpolator slices a finished fill gave back ({@code NoiseChunkMixin}), for the next NoiseChunks' interpolators.
 *
 * <p>Each interpolator has two slices. A slice holds a column of corners per cell row, {@code [cellCountXZ + 1][cellCountY + 1]}.
 * The slices are made with their NoiseChunk (the biome step) and read only by the fill (the noise step). So they outlived young
 * collections and were copied into the old generation before they turned to garbage: about 33 KB a chunk in the overworld. A fill
 * writes every entry of both slices before reading any. {@code fillSlice} fills a whole slice, and {@code selectCellYZ} reads after
 * both are filled. So a slice from here holds old values nobody reads. At most a few hundred slices per shape are kept.
 */
public final class SlicePool {
    private SlicePool() {}

    /** Slices kept per shape (about 2 KB each for a chunk in the overworld). */
    private static final int MAX = 256;

    private static final class Stack {
        final double[][][] items = new double[MAX][][];
        int size;
    }

    private static final ConcurrentHashMap<Integer, Stack> BY_SHAPE = new ConcurrentHashMap<>();

    private static Stack stack(int rows, int height) {
        Integer key = rows << 16 | height;
        Stack s = BY_SHAPE.get(key);
        if (s == null) {
            s = BY_SHAPE.computeIfAbsent(key, k -> new Stack());
        }
        return s;
    }

    /** A slice of {@code rows} rows of {@code height} values, or null. Its values are stale. The caller writes them all first. */
    @Nullable
    public static double[][] take(int rows, int height) {
        if (rows > 0xFFFF || height > 0xFFFF) {
            return null;
        }
        Stack s = stack(rows, height);
        synchronized (s) {
            if (s.size == 0) {
                return null;
            }
            double[][] slice = s.items[--s.size];
            s.items[s.size] = null;
            return slice;
        }
    }

    /** Gives back a slice nothing refers to any more. */
    public static void give(double[][] slice) {
        int rows = slice.length;
        if (rows == 0 || rows > 0xFFFF || slice[0].length > 0xFFFF) {
            return;
        }
        Stack s = stack(rows, slice[0].length);
        synchronized (s) {
            if (s.size < MAX) {
                s.items[s.size++] = slice;
            }
        }
    }
}
