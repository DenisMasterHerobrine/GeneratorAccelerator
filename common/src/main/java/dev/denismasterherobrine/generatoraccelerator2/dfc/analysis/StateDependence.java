package dev.denismasterherobrine.generatoraccelerator2.dfc.analysis;

import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.Node;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * What a router field's point values (evaluated inside a NoiseChunk with point contexts) can depend
 * on besides the position.
 *
 * <ul>
 * <li>Call order: the chunk's {@code Cache2D} returns the value of the last (x, z) whatever the y,
 *     so it is order-sensitive when its child reads y.</li>
 * <li>The chunk: a {@code FlatCache} answers from the chunk's quart grid (sampled at y = 0) for
 *     points inside the chunk, and evaluates directly outside it. The two differ when the cache's
 *     child reads y. The beardifier is the chunk's own structure set.</li>
 * </ul>
 * Interpolated, once- and cell-cache wrappers evaluate their child directly for point contexts.
 * Functions the compiler could not lower run their own {@code compute} and are treated as unknown.
 * Old-chunk blending is per chunk too and is checked by callers.
 */
public final class StateDependence {
    private StateDependence() {}

    /** Evaluating the fields at extra points, or in another order, cannot change any value vanilla computes. */
    public static boolean orderIndependent(Map<String, Node> roots, String... fields) {
        return orderIndependent(roots, Set.of(), fields);
    }

    /** {@link #orderIndependent(Map, String...)}, not counting the markers in {@code checked} (and what is below them). */
    public static boolean orderIndependent(Map<String, Node> roots, Set<Node> checked, String... fields) {
        CoordDeps deps = new CoordDeps(false);
        Map<Node, Boolean> seen = new IdentityHashMap<>();
        for (Node n : checked) {
            seen.put(n, Boolean.TRUE);
        }
        return all(roots, fields, seen, n -> !(n instanceof Node.Unsupported) && !ySensitiveCache2d(n, deps));
    }

    private static boolean ySensitiveCache2d(Node n, CoordDeps deps) {
        return n instanceof Node.Marker m && m.type() == Node.MarkerType.CACHE_2D && !deps.of(m.child()).isXzOrConst();
    }

    /** The router field whose order-sensitive cache_2d markers the aquifer can rule out chunk by chunk ({@link #cellCentreCaches}). */
    public static final String FLOODEDNESS = "fluid_level_floodedness";

    /**
     * The cache_2d markers over a function that reads y below {@code fluid_level_floodedness}, when the aquifer can rule out
     * chunk by chunk that they make a value depend on the order of evaluations. Regions Unexplored 0.6 wraps the field in a
     * {@code range_choice} over such a marker. The aquifer evaluates the field only at its cells' centres, once per cell.
     * The chunk's {@code Cache2D} keeps only the last block column it was asked. So a value can differ from a direct
     * evaluation only where two cells share a block column and the marker's function differs between their two heights.
     * {@code NoiseBasedAquiferMixin} checks this over its cells. The check is valid under three conditions. First, every
     * marker is reached from that field alone. The chunk wraps a marker once, so another field's evaluations would
     * interleave with the aquifer's in the same cache. Second, the path to the marker passes only through nodes that
     * evaluate their children at the point they are asked. Third, the marker's function evaluates directly, as the chunk's
     * wrapper would: no other cache over a function that reads y, and no blending, beardifier or uncompiled code below it.
     *
     * @return the markers with their vanilla functions (whose {@code compute} evaluates the function directly), empty when
     *     the field has no such marker, or null when it has one that cannot be checked this way
     */
    public static Map<Node, DensityFunction> cellCentreCaches(Map<String, Node> roots) {
        Node root = roots.get(FLOODEDNESS);
        if (root == null) {
            return null;
        }
        CoordDeps deps = new CoordDeps(false);
        Map<Node, DensityFunction> found = new IdentityHashMap<>();
        Map<Node, Boolean> seen = new IdentityHashMap<>();
        ArrayDeque<Node> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Node n = stack.pop();
            if (seen.put(n, Boolean.TRUE) != null) {
                continue;
            }
            if (ySensitiveCache2d(n, deps)) {
                Node.Marker m = (Node.Marker) n;
                if (!evaluatesDirectly(m.child(), deps)) {
                    return null;
                }
                found.put(m, m.vanilla());
                continue;
            }
            boolean samePoint = switch (n) {
                case Node.Ap2 a -> true;
                case Node.MulOrAdd m -> true;
                case Node.Mapped m -> true;
                case Node.Clamp c -> true;
                case Node.RangeChoice r -> true;
                case Node.ShiftedNoise s -> true;
                case Node.Weird w -> true;
                case Node.Spline s -> true;
                case Node.Marker m -> m.type() != Node.MarkerType.CACHE_2D || deps.of(m.child()).isXzOrConst();
                default -> RefCount.children(n).isEmpty();
            };
            if (!samePoint) {
                // A node that may evaluate its children elsewhere (lifted or modded code). Fine only with no such marker below.
                if (!orderIndependentBelow(n, deps)) {
                    return null;
                }
                continue;
            }
            for (Node c : RefCount.children(n)) {
                stack.push(c);
            }
        }
        if (found.isEmpty()) {
            return found;
        }
        // No other field may reach a marker. The chunk's single wrapper would see that field's evaluations too.
        for (Map.Entry<String, Node> e : roots.entrySet()) {
            if (e.getKey().equals(FLOODEDNESS) || e.getValue() == null) {
                continue;
            }
            Map<Node, Boolean> other = new IdentityHashMap<>();
            ArrayDeque<Node> s = new ArrayDeque<>();
            s.push(e.getValue());
            while (!s.isEmpty()) {
                Node n = s.pop();
                if (other.put(n, Boolean.TRUE) != null) {
                    continue;
                }
                if (found.containsKey(n)) {
                    return null;
                }
                for (Node c : RefCount.children(n)) {
                    s.push(c);
                }
            }
        }
        return found;
    }

    /** No uncompiled code and no cache_2d over a function that reads y anywhere below {@code n}. */
    private static boolean orderIndependentBelow(Node n, CoordDeps deps) {
        Map<Node, Boolean> seen = new IdentityHashMap<>();
        ArrayDeque<Node> stack = new ArrayDeque<>();
        stack.push(n);
        while (!stack.isEmpty()) {
            Node c = stack.pop();
            if (seen.put(c, Boolean.TRUE) != null) {
                continue;
            }
            if (c instanceof Node.Unsupported || ySensitiveCache2d(c, deps)) {
                return false;
            }
            stack.addAll(RefCount.children(c));
        }
        return true;
    }

    /**
     * Whether the chunk's wrapper of a function evaluates the function at a point context as the vanilla object does. Point
     * contexts pass through interpolators and once- and cell-caches. A flat cache or cache_2d over a function that does not
     * read y gives the function's value. Nothing below may read chunk state (blending, the beardifier) or run uncompiled code.
     */
    private static boolean evaluatesDirectly(Node n, CoordDeps deps) {
        Map<Node, Boolean> seen = new IdentityHashMap<>();
        ArrayDeque<Node> stack = new ArrayDeque<>();
        stack.push(n);
        while (!stack.isEmpty()) {
            Node c = stack.pop();
            if (seen.put(c, Boolean.TRUE) != null) {
                continue;
            }
            if (c instanceof Node.Unsupported || c instanceof Node.Beardifier || c instanceof Node.BlendDensity
                || c instanceof Node.BlendAlpha || c instanceof Node.BlendOffset) {
                return false;
            }
            if (c instanceof Node.Marker m && (m.type() == Node.MarkerType.CACHE_2D || m.type() == Node.MarkerType.FLAT_CACHE)
                && !deps.of(m.child()).isXzOrConst()) {
                return false;
            }
            stack.addAll(RefCount.children(c));
        }
        return true;
    }

    /** Why {@link #orderIndependent} says no (for the log), or null when it says yes. */
    public static String orderDependence(Map<String, Node> roots, String... fields) {
        CoordDeps deps = new CoordDeps(false);
        for (String field : fields) {
            Node root = roots.get(field);
            if (root == null) {
                return field + " is missing";
            }
            Map<Node, Boolean> seen = new IdentityHashMap<>();
            ArrayDeque<Node> stack = new ArrayDeque<>();
            stack.push(root);
            while (!stack.isEmpty()) {
                Node n = stack.pop();
                if (seen.put(n, Boolean.TRUE) != null) {
                    continue;
                }
                if (n instanceof Node.Unsupported u) {
                    return field + ": uncompiled " + Names.mojangName(u.vanilla().getClass());
                }
                if (n instanceof Node.Marker m && m.type() == Node.MarkerType.CACHE_2D && !deps.of(m.child()).isXzOrConst()) {
                    return field + ": cache_2d over y-reader (" + deps.firstReadingY(m.child()) + ")";
                }
                for (Node c : RefCount.children(n)) {
                    stack.push(c);
                }
            }
        }
        return null;
    }

    /** Additionally, every NoiseChunk of the same RandomState (without blending) computes the same point values. */
    public static boolean chunkIndependent(Map<String, Node> roots, String... fields) {
        CoordDeps deps = new CoordDeps(false);
        return all(roots, fields, n -> !(n instanceof Node.Unsupported) && !(n instanceof Node.Beardifier)
            && !(n instanceof Node.Marker m && (m.type() == Node.MarkerType.CACHE_2D || m.type() == Node.MarkerType.FLAT_CACHE)
                && !deps.of(m.child()).isXzOrConst()));
    }

    private static boolean all(Map<String, Node> roots, String[] fields, Predicate<Node> ok) {
        return all(roots, fields, new IdentityHashMap<>(), ok);
    }

    private static boolean all(Map<String, Node> roots, String[] fields, Map<Node, Boolean> seen, Predicate<Node> ok) {
        ArrayDeque<Node> stack = new ArrayDeque<>();
        for (String field : fields) {
            Node root = roots.get(field);
            if (root == null) {
                return false;
            }
            stack.push(root);
        }
        while (!stack.isEmpty()) {
            Node n = stack.pop();
            if (seen.put(n, Boolean.TRUE) != null) {
                continue;
            }
            if (!ok.test(n)) {
                return false;
            }
            for (Node c : RefCount.children(n)) {
                stack.push(c);
            }
        }
        return true;
    }
}
