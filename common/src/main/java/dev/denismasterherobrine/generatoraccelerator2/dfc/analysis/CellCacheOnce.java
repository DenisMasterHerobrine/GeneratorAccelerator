package dev.denismasterherobrine.generatoraccelerator2.dfc.analysis;

import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.Node;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * How vanilla's cell fill reaches the {@code cache_once} markers a cell program evaluates.
 *
 * <p>Vanilla fills a cell cache with {@code fillArray} from the root down. A node fills its array-path children as
 * whole arrays: both operands of {@code add}, the first operand of {@code mul}/{@code min}/{@code max}, the input of a
 * mapping, clamp, blend or weird sampler, and a range choice's input. The node evaluates the rest point by point with
 * {@code compute}: the other operands, range choice branches, spline coordinates, shifted noise offsets, and anything
 * under a modded function. A {@code cache_once} filled as an array keeps that array for the cell, so every block gets
 * its own value. A {@code cache_once} reached point by point returns the value it computed first until the chunk's
 * interpolation counter moves. The counter does not move inside a cell, so a whole cell shares one value.
 *
 * <p>Cell programs evaluate per block. An array-path {@code cache_once} lowers to its child, evaluated per block
 * (exact). A point-path {@code cache_once} is unsupported. The cell falls back to vanilla's fill.
 *
 * <p>The program asks a {@code cache_2d} it keeps as a wrapper block by block ({@code compute}). So a
 * {@code cache_once} below the wrapper is reached point by point there. Vanilla does the same when the wrapper is on
 * its point path. When the wrapper is on the array path, vanilla fills it as an array, past its memo, so every block
 * gets its own value. Such a cell keeps vanilla's fill too. (The program's column caches never hold a
 * {@code cache_once}.)
 */
public final class CellCacheOnce {
    private CellCacheOnce() {}

    /** True when the cell program would reach some {@code cache_once} under {@code root} differently from vanilla's cell fill. */
    public static boolean onPointPath(Node root) {
        return walk(root, true, new IdentityHashMap<>());
    }

    /**
     * True when evaluating {@code n} block by block inside a cell can reach a {@code cache_once}. The walk passes through
     * {@code cache_2d} wrappers. It does not enter what the cell reads from elsewhere (interpolated corners, flat and
     * cell caches).
     */
    public static boolean reachesCacheOnce(Node n) {
        return reaches(n, new IdentityHashMap<>());
    }

    private static boolean reaches(Node n, Map<Node, Integer> seen) {
        if (seen.put(n, 1) != null) {
            return false;
        }
        if (n instanceof Node.Marker m) {
            return switch (m.type()) {
                case CACHE_ONCE -> true;
                case CACHE_2D -> reaches(m.child(), seen);
                case INTERPOLATED, FLAT_CACHE, CACHE_ALL_IN_CELL -> false;
            };
        }
        for (Node c : RefCount.children(n)) {
            if (reaches(c, seen)) {
                return true;
            }
        }
        return false;
    }

    private static boolean walk(Node n, boolean array, Map<Node, Integer> seen) {
        int bit = array ? 1 : 2;
        Integer before = seen.get(n);
        if (before != null && (before & bit) != 0) {
            return false;
        }
        seen.put(n, (before == null ? 0 : before) | bit);
        if (n instanceof Node.Marker m) {
            return switch (m.type()) {
            case CACHE_ONCE -> !array || walk(m.child(), true, seen);
            // Asked block by block. On vanilla's array path, a cache_once below would get the wrapper's point semantics.
            case CACHE_2D -> array && reachesCacheOnce(m.child());
            // the cell program reads these (corners, caches). It never evaluates their children per block
            case INTERPOLATED, FLAT_CACHE, CACHE_ALL_IN_CELL -> false;
        };
        } else if (n instanceof Node.Ap2 a) {
            return walk(a.a(), array, seen) || walk(a.b(), array && a.type() == Node.Ap2Type.ADD, seen);
        } else if (n instanceof Node.MulOrAdd m) {
            return walk(m.input(), array, seen);
        } else if (n instanceof Node.Mapped m) {
            return walk(m.input(), array, seen);
        } else if (n instanceof Node.Clamp c) {
            return walk(c.input(), array, seen);
        } else if (n instanceof Node.BlendDensity b) {
            return walk(b.input(), array, seen);
        } else if (n instanceof Node.Weird w) {
            return walk(w.input(), array, seen);
        } else if (n instanceof Node.RangeChoice r) {
            return walk(r.input(), array, seen) || walk(r.inRange(), false, seen) || walk(r.outRange(), false, seen);
        } else {
            for (Node c : RefCount.children(n)) {
                if (walk(c, false, seen)) {
                    return true;
                }
            }
            return false;
        }
    }
}
