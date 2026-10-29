package dev.denismasterherobrine.generatoraccelerator2.gpu.runtime;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * The GPU's climate points and searches, handed to the CPU's own biome lookups of a chunk.
 *
 * <p>Some mods change the biome lookup itself: Biolith replaces it with its own search and its replacements, Alex's Caves puts its
 * cave biomes over it. The GPU cannot run their code, so it does not decide such a dimension's biomes. It still computes what
 * their code spends most of its time on. For each quart of the chunk it computes the climate point and, in the quart's tree, the
 * nearest leaf and the nearest leaf of another biome (after TerraBlender's placeholder also in region 0's tree). The CPU then runs
 * the biome step as it is, every mod's code included. While it fills the chunk, three of GA2's own hooks take the GPU's answers:
 * <ul>
 *   <li>{@code Climate.Sampler.sample} of the chunk's own sampler at one of its quarts returns the GPU's point;</li>
 *   <li>vanilla's search ({@code biome.ClimateSearch}, TerraBlender's region searches included) returns the nearest leaf;</li>
 *   <li>Biolith's search ({@code compat.biolith.BiolithSearch}) returns both leaves.</li>
 * </ul>
 * A search takes the GPU's answer only for the tree the GPU searched and for the point it was handed last, compared by value.
 * Any other search runs as before.
 *
 * <p>The answers are exact. The GPU computes the climate point as {@code sample} does (a value beyond an int is left to the CPU).
 * It hands a leaf only where it is the only leaf at its distance. Vanilla's answer then does not depend on the thread's last result.
 * Biolith's two answers then do not depend on its last answers either, provided those are of two biomes (see
 * {@code FlatClimateTree.searchFittest}). Biolith's search checks that before it takes them. Each search still leaves the thread's
 * last results as its own walk would, so later searches on the CPU start where they would have.
 *
 * <p>{@code -Dgeneratoraccelerator2.gpu.handBiomes=false} turns this off.
 */
public final class BiomeHand {
    public static final boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.gpu.handBiomes"));

    /** Chunks filled with handed answers, and the points and searches the GPU answered in them. */
    public static final LongAdder CHUNKS = new LongAdder(), POINTS = new LongAdder(), NEAREST = new LongAdder(), FITTEST = new LongAdder();

    private static final ThreadLocal<BiomeHand> CURRENT = new ThreadLocal<>();
    /** Threads inside a handed fill. While it is zero the hooks skip the thread-local read. */
    private static final AtomicInteger ACTIVE = new AtomicInteger();

    private final GpuWorldgen wg;
    private final GpuWorldgen.BiomeResult r;
    private final ChunkAccess chunk;
    private final int qx0, qz0, qy0, qyn;
    /** The sampler the chunk's fill asks with ({@link #bind}). Only its samples are handed. */
    private Climate.Sampler sampler;
    /** Per quart, the point once handed. */
    private final Climate.TargetPoint[] points;
    /** The quart whose point was handed last, or -1. */
    private int last = -1;
    private final Climate.RTree.Leaf<?>[] pair = new Climate.RTree.Leaf<?>[2];
    private int handedPoints, handedNearest, handedFittest;

    private BiomeHand(GpuWorldgen wg, GpuWorldgen.BiomeResult r, ChunkAccess chunk) {
        this.wg = wg;
        this.r = r;
        this.chunk = chunk;
        this.qx0 = chunk.getPos().x() * 4;
        this.qz0 = chunk.getPos().z() * 4;
        this.qy0 = wg.minY >> 2;
        this.qyn = wg.height >> 2;
        this.points = new Climate.TargetPoint[16 * qyn];
    }

    /** Runs {@code body}, the chunk's biome step, with the GPU's answers {@code r} handed to its lookups on this thread. */
    public static <T> T fill(GpuWorldgen wg, ChunkAccess chunk, GpuWorldgen.BiomeResult r, Supplier<T> body) {
        BiomeHand h = new BiomeHand(wg, r, chunk);
        BiomeHand previous = CURRENT.get();
        ACTIVE.incrementAndGet();
        CURRENT.set(h);
        try {
            return body.get();
        } finally {
            CURRENT.set(previous);
            ACTIVE.decrementAndGet();
            CHUNKS.increment();
            POINTS.add(h.handedPoints);
            NEAREST.add(h.handedNearest);
            FITTEST.add(h.handedFittest);
        }
    }

    @Nullable
    private static BiomeHand current() {
        return ACTIVE.get() == 0 ? null : CURRENT.get();
    }

    /** Whether this thread runs a handed biome step. The step's work then runs in place, on this thread. */
    public static boolean inFill() {
        return current() != null;
    }

    /** The chunk's fill starts with {@code sampler} (the fill hook). The handed points are that sampler's. */
    public static void bind(ChunkAccess chunk, Climate.Sampler sampler) {
        BiomeHand h = current();
        if (h != null && h.chunk == chunk && h.sampler == null) {
            h.sampler = sampler;
        }
    }

    /** The GPU's point for {@code sampler.sample(x, y, z)}, or null to sample it. */
    @Nullable
    public static Climate.TargetPoint point(Climate.Sampler sampler, int x, int y, int z) {
        BiomeHand h = current();
        if (h == null || h.sampler != sampler) {
            return null;
        }
        int dx = x - h.qx0, dz = z - h.qz0, dy = y - h.qy0;
        if (dx < 0 || dx >= 4 || dz < 0 || dz >= 4 || dy < 0 || dy >= h.qyn) {
            return null;
        }
        int i = dy * 16 + dx * 4 + dz;
        Climate.TargetPoint p = h.points[i];
        if (p == null) {
            p = h.wg.target(h.r, i);
            if (p == null) {
                return null;
            }
            h.points[i] = p;
        }
        h.last = i;
        h.handedPoints++;
        return p;
    }

    /** The last handed quart when its point has these values, else -1. */
    private int quartOf(long q0, long q1, long q2, long q3, long q4, long q5) {
        if (last < 0) {
            return -1;
        }
        Climate.TargetPoint p = points[last];
        return p.temperature() == q0 && p.humidity() == q1 && p.continentalness() == q2 && p.erosion() == q3 && p.depth() == q4
            && p.weirdness() == q5 ? last : -1;
    }

    /** The GPU's node of answer {@code k} (0: the quart's tree, 2: region 0's tree) when it is in {@code tree}, else -1. */
    private int node(int quart, int k, Climate.RTree<?> tree) {
        int u = r.hand()[quart * 4 + k];
        return u >= 0 && wg.biomeTree(wg.treeOf(u)) == tree ? u : -1;
    }

    /**
     * Vanilla's search of {@code tree} at the point of these values ({@code RTree.search} with {@code Node::distance}): the GPU's
     * nearest leaf, or null to search. The caller sets the tree's last result to it, as the search does.
     */
    @Nullable
    @SuppressWarnings("unchecked")
    public static <T> Climate.RTree.Leaf<T> nearest(Climate.RTree<T> tree, long q0, long q1, long q2, long q3, long q4, long q5) {
        BiomeHand h = current();
        if (h == null) {
            return null;
        }
        int i = h.quartOf(q0, q1, q2, q3, q4, q5);
        if (i < 0) {
            return null;
        }
        int u = h.node(i, 0, tree);
        if (u < 0) {
            u = h.node(i, 2, tree);
        }
        if (u < 0) {
            return null;
        }
        h.handedNearest++;
        return (Climate.RTree.Leaf<T>) h.wg.leaf(u);
    }

    /**
     * Biolith's search of {@code tree} at {@code target}: the GPU's nearest leaf and nearest leaf of another biome (null for none) in a
     * reused array, or null to search. The caller checks that the thread's last answers are of two biomes before it takes them.
     */
    @Nullable
    public static Climate.RTree.Leaf<?>[] fittest(Climate.RTree<?> tree, Climate.TargetPoint target) {
        BiomeHand h = current();
        if (h == null) {
            return null;
        }
        int i = h.quartOf(target.temperature(), target.humidity(), target.continentalness(), target.erosion(), target.depth(), target.weirdness());
        if (i < 0) {
            return null;
        }
        int k = 0;
        int u = h.node(i, 0, tree);
        if (u < 0) {
            k = 2;
            u = h.node(i, 2, tree);
        }
        if (u < 0) {
            return null;
        }
        int p = h.r.hand()[i * 4 + k + 1];
        if (p == -1 || p < -2) {
            return null;
        }
        h.pair[0] = h.wg.leaf(u);
        h.pair[1] = p >= 0 ? h.wg.leaf(p) : null;
        h.handedFittest++;
        return h.pair;
    }
}
