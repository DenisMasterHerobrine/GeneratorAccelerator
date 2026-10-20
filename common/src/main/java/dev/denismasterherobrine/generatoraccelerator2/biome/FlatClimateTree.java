package dev.denismasterherobrine.generatoraccelerator2.biome;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.biome.Climate;

/**
 * Vanilla's climate search tree ({@code Climate.RTree}) laid out in flat arrays, searched with exactly
 * vanilla's traversal.
 *
 * <p>{@code SubTree.search} keeps its best leaf and that leaf's distance, and visits the children in order.
 * It enters a child, or takes a leaf, only when the child's distance is strictly smaller than the best so far.
 * A child subtree hands back a better leaf only when it found a strictly smaller distance. This amounts to one
 * depth-first walk over a single (best, distance) pair, seeded with the thread's last result. {@link #search}
 * performs that walk. Ties therefore resolve exactly as in vanilla, including the dependence on the last
 * result. Distances use vanilla's per-dimension formula and wrapping {@code long} arithmetic. Even overflowing
 * datapack inputs therefore give the same answer. The seventh dimension (the offset) is compared with a query
 * value that is always 0, so its term is a per-node constant.
 *
 * <p>Children of a node are stored next to each other (breadth-first ids), so a child's id is the index
 * of its slot.
 *
 * <p>The walk's answer depends on the last result only through ties. A node is never nearer than a leaf below it.
 * Every subtree holding a nearest leaf is therefore entered while the best distance is larger. The walk ends on the
 * last result when the last result is one of the nearest leaves. Otherwise it ends on the first nearest leaf in walk
 * order. When a single leaf is nearest, that leaf is the answer regardless of the last result. The column searches
 * rely on this. Along a quart column only the depth changes, and the other five values are fixed for the column.
 * Leaves with the same depth range share the depth term. The nearest leaf of each such group over the other
 * dimensions is found once per column ({@link #groupAnswers}). A query then only compares the groups. A nearest
 * distance shared by two leaves goes to the walk, which resolves the tie as vanilla does.
 */
public final class FlatClimateTree {
    /** Six lower and upper bounds per node: {@code bounds[n * 12 + 2 * d]} and {@code [... + 1]}. */
    private final long[] bounds;
    /** The seventh dimension's squared distance to 0. */
    private final long[] offsetTerm;
    private final int[] first;
    private final int[] count;
    private final Climate.RTree.Leaf<?>[] leaves;
    private final int depth;
    /** Every bound and offset is within {@link #SMALL}. A node's distance terms then never overflow (see {@link #search}). */
    private final boolean small;

    /** With all bounds and queries within this, each squared term is below 2^60 and seven of them below 2^63. */
    private static final long SMALL = 1L << 29;

    private static boolean within(long v) {
        return v >= -SMALL && v <= SMALL;
    }

    private static final ThreadLocal<int[]> STACK = ThreadLocal.withInitial(() -> new int[64]);

    /** Per-tree data kept by compat code, e.g. Biolith's biome numbers per leaf. Built once on first use. */
    public volatile Object compatData;

    private FlatClimateTree(long[] bounds, long[] offsetTerm, int[] first, int[] count, Climate.RTree.Leaf<?>[] leaves, int depth, boolean small) {
        this.small = small;
        this.bounds = bounds;
        this.offsetTerm = offsetTerm;
        this.first = first;
        this.count = count;
        this.leaves = leaves;
        this.depth = depth;
        ALL.add(this);
    }

    /** Builds the flat tree. {@code indexer} records each leaf's id in a field on the leaf. */
    public static FlatClimateTree build(Climate.RTree<?> tree, LeafIndexer indexer) {
        List<Climate.RTree.Node<?>> order = new ArrayList<>();
        List<int[]> children = new ArrayList<>();
        order.add(tree.root);
        // Breadth-first ids. The children of every node get consecutive ids.
        int head = 0;
        while (head < order.size()) {
            Climate.RTree.Node<?> node = order.get(head++);
            if (node instanceof Climate.RTree.SubTree<?> sub) {
                int start = order.size();
                for (Climate.RTree.Node<?> child : sub.children) {
                    order.add(child);
                }
                children.add(new int[] { start, sub.children.length });
            } else {
                children.add(new int[] { 0, 0 });
            }
        }
        int nodes = order.size();
        long[] bounds = new long[nodes * 12];
        long[] offsetTerm = new long[nodes];
        int[] first = new int[nodes];
        int[] count = new int[nodes];
        Climate.RTree.Leaf<?>[] leaves = new Climate.RTree.Leaf<?>[nodes];
        boolean small = true;
        for (int id = 0; id < nodes; id++) {
            Climate.RTree.Node<?> node = order.get(id);
            Climate.Parameter[] space = node.parameterSpace;
            for (int d = 0; d < 6; d++) {
                bounds[id * 12 + 2 * d] = space[d].min();
                bounds[id * 12 + 2 * d + 1] = space[d].max();
            }
            long t = distance(space[6].min(), space[6].max(), 0L);
            offsetTerm[id] = t * t;
            for (int d = 0; d < 7; d++) {
                small &= within(space[d].min()) && within(space[d].max());
            }
            first[id] = children.get(id)[0];
            count[id] = children.get(id)[1];
            if (node instanceof Climate.RTree.Leaf<?> leaf) {
                if (count[id] != 0) {
                    throw new IllegalStateException("leaf with children");
                }
                leaves[id] = leaf;
                indexer.index(leaf, id);
            } else if (count[id] == 0) {
                throw new IllegalStateException("unknown climate tree node " + node.getClass());
            }
        }
        return new FlatClimateTree(bounds, offsetTerm, first, count, leaves, depthOf(first, count, 0), small);
    }

    private static int depthOf(int[] first, int[] count, int node) {
        int d = 0;
        for (int c = first[node]; c < first[node] + count[node]; c++) {
            d = Math.max(d, depthOf(first, count, c));
        }
        return d + 1;
    }

    /** Vanilla {@code Climate.Parameter.distance}, including its behaviour for an inverted range. */
    private static long distance(long min, long max, long q) {
        long above = q - max;
        long below = min - q;
        return above > 0L ? above : Math.max(below, 0L);
    }

    private long nodeDistance(int id, long q0, long q1, long q2, long q3, long q4, long q5) {
        long[] b = this.bounds;
        int o = id * 12;
        long s = this.offsetTerm[id];
        long t;
        t = distance(b[o], b[o + 1], q0);
        s += t * t;
        t = distance(b[o + 2], b[o + 3], q1);
        s += t * t;
        t = distance(b[o + 4], b[o + 5], q2);
        s += t * t;
        t = distance(b[o + 6], b[o + 7], q3);
        s += t * t;
        t = distance(b[o + 8], b[o + 9], q4);
        s += t * t;
        t = distance(b[o + 10], b[o + 11], q5);
        s += t * t;
        return s;
    }

    /**
     * {@link #nodeDistance}, or any value not below {@code limit} as soon as the sum reaches {@code limit}. Valid only when
     * every term is a non-negative square that cannot overflow, i.e. bounds and query are within {@link #SMALL}. The partial
     * sums then only grow. A node whose sum reaches the best distance so far loses vanilla's strict comparison either way.
     * A returned value below {@code limit} is the full sum.
     */
    private long nodeDistanceBelow(int id, long q0, long q1, long q2, long q3, long q4, long q5, long limit) {
        long[] b = this.bounds;
        int o = id * 12;
        long s = this.offsetTerm[id];
        long t;
        t = distance(b[o + 4], b[o + 5], q2);
        s += t * t;
        t = distance(b[o + 6], b[o + 7], q3);
        s += t * t;
        if (s >= limit) {
            return s;
        }
        t = distance(b[o], b[o + 1], q0);
        s += t * t;
        t = distance(b[o + 2], b[o + 3], q1);
        s += t * t;
        if (s >= limit) {
            return s;
        }
        t = distance(b[o + 8], b[o + 9], q4);
        s += t * t;
        t = distance(b[o + 10], b[o + 11], q5);
        s += t * t;
        return s;
    }

    /**
     * The leaf vanilla's search returns for this target when the thread's last result is {@code last}.
     * {@code last} is a leaf id, or -1 for none.
     */
    public int search(long q0, long q1, long q2, long q3, long q4, long q5, int last) {
        if (this.count[0] == 0) {
            return 0;
        }
        int best = last;
        long bestDistance = last < 0 ? Long.MAX_VALUE : nodeDistance(last, q0, q1, q2, q3, q4, q5);
        int[] stack = STACK.get();
        if (stack.length < 2 * (this.depth + 1)) {
            stack = new int[2 * (this.depth + 1)];
            STACK.set(stack);
        }
        int[] first = this.first;
        int[] count = this.count;
        boolean bounded = this.small && within(q0) && within(q1) && within(q2) && within(q3) && within(q4) && within(q5);
        // stack of (node, next child slot)
        int sp = 0;
        stack[0] = 0;
        stack[1] = first[0];
        while (sp >= 0) {
            int node = stack[2 * sp];
            int child = stack[2 * sp + 1];
            if (child == first[node] + count[node]) {
                sp--;
                continue;
            }
            stack[2 * sp + 1] = child + 1;
            long d = bounded ? nodeDistanceBelow(child, q0, q1, q2, q3, q4, q5, bestDistance) : nodeDistance(child, q0, q1, q2, q3, q4, q5);
            if (bestDistance > d) {
                if (count[child] == 0) {
                    bestDistance = d;
                    best = child;
                } else {
                    sp++;
                    stack[2 * sp] = child;
                    stack[2 * sp + 1] = first[child];
                }
            }
        }
        return best;
    }

    /**
     * Biolith's search ({@code MixinSearchTree.biolith$searchTreeGet} with vanilla's metric). Finds the nearest leaf and the
     * nearest leaf of another biome, seeded with the thread's last two answers. The walk uses vanilla's depth-first order. It
     * enters a child, or takes a leaf, only when the child is strictly nearer than the second answer so far. A leaf nearer
     * than the first answer replaces the first answer. The old first then becomes the second when the two biomes differ. A
     * leaf between the two answers replaces the second when its biome differs from the first's. Ties keep the earlier leaf,
     * as in Biolith.
     *
     * <p>The first answer ends as the nearest leaf, as for {@link #search}. The second always has a different biome than the
     * first, provided the last answers do. The second ends at the smallest distance of a leaf whose biome differs from the
     * final first. Such a leaf is entered while the second distance is larger. It then either becomes the second, or meets a
     * first of its own biome. In that case the first's leaf becomes the second when the first moves to another biome. So when
     * each of the two distances belongs to a single leaf, the answers do not depend on the last answers
     * ({@link #groupFittest}).
     *
     * @param q0 the target point's six climate values, {@code q0} to {@code q5}
     * @param keys    each node id's biome as a number. Equal biomes get equal numbers. The entry at the node count is the
     *                number of Biolith's placeholder for no leaf
     * @param ultimate    the thread's last first answer as an id ({@code -1} for none)
     * @param penultimate the thread's last second answer as an id ({@code -1} for none)
     * @param ids     receives the first and second answer ({@code -1} for no second)
     * @param distance receives their distances
     */
    public void searchFittest(long q0, long q1, long q2, long q3, long q4, long q5, int[] keys, int ultimate, int penultimate, int[] ids, long[] distance) {
        int none = this.first.length;
        int u = ultimate;
        int p = penultimate;
        long du = u < 0 ? Long.MAX_VALUE : nodeDistance(u, q0, q1, q2, q3, q4, q5);
        long dp = p < 0 ? Long.MAX_VALUE : nodeDistance(p, q0, q1, q2, q3, q4, q5);
        if (du > dp) {
            int t = u;
            u = p;
            p = t;
            long td = du;
            du = dp;
            dp = td;
        }
        int[] stack = STACK.get();
        if (stack.length < 2 * (this.depth + 1)) {
            stack = new int[2 * (this.depth + 1)];
            STACK.set(stack);
        }
        int[] first = this.first;
        int[] count = this.count;
        boolean bounded = this.small && within(q0) && within(q1) && within(q2) && within(q3) && within(q4) && within(q5);
        int sp = 0;
        stack[0] = 0;
        stack[1] = first[0];
        while (sp >= 0) {
            int node = stack[2 * sp];
            int child = stack[2 * sp + 1];
            if (child == first[node] + count[node]) {
                sp--;
                continue;
            }
            stack[2 * sp + 1] = child + 1;
            long d = bounded ? nodeDistanceBelow(child, q0, q1, q2, q3, q4, q5, dp) : nodeDistance(child, q0, q1, q2, q3, q4, q5);
            if (dp > d) {
                if (count[child] == 0) {
                    boolean otherBiome = keys[child] != keys[u < 0 ? none : u];
                    if (du > d) {
                        if (otherBiome) {
                            p = u;
                            dp = du;
                        }
                        u = child;
                        du = d;
                    } else if (otherBiome) {
                        p = child;
                        dp = d;
                    }
                } else {
                    sp++;
                    stack[2 * sp] = child;
                    stack[2 * sp + 1] = first[child];
                }
            }
        }
        ids[0] = u;
        ids[1] = p;
        distance[0] = du;
        distance[1] = dp;
    }

    /**
     * Per thread: each node's distance without the depth term, for the most recently asked columns. That partial distance
     * covers the other five dimensions plus the offset. Along a quart column only the depth changes, for the climate
     * parameters a sampler computes per column. The biome fill asks its 16 columns in turn, section after section. A node's
     * other terms are therefore computed once per column. A slot is keyed by its five non-depth values and the tree. A fresh
     * stamp makes the cached part of every node stale.
     */
    private static final class ColumnParts {
        static final int SLOTS = 16;
        final long[] keys = new long[SLOTS * 5];
        final FlatClimateTree[] trees = new FlatClimateTree[SLOTS];
        final int[] slotStamp = new int[SLOTS];
        long[][] parts = new long[SLOTS][];
        int[][] stamps = new int[SLOTS][];
        int victim;
        int stampCounter;

        int slot(FlatClimateTree tree, long q0, long q1, long q2, long q3, long q5) {
            for (int s = 0; s < SLOTS; s++) {
                int k = s * 5;
                if (this.trees[s] == tree && this.keys[k] == q0 && this.keys[k + 1] == q1 && this.keys[k + 2] == q2 && this.keys[k + 3] == q3
                    && this.keys[k + 4] == q5) {
                    return s;
                }
            }
            int s = this.victim;
            this.victim = (s + 1) & (SLOTS - 1);
            int k = s * 5;
            this.keys[k] = q0;
            this.keys[k + 1] = q1;
            this.keys[k + 2] = q2;
            this.keys[k + 3] = q3;
            this.keys[k + 4] = q5;
            this.trees[s] = tree;
            int nodes = tree.first.length;
            if (this.parts[s] == null || this.parts[s].length < nodes) {
                this.parts[s] = new long[nodes];
                this.stamps[s] = new int[nodes];
            }
            if (++this.stampCounter == 0) {
                // The counter wrapped after 2^32 columns. No old stamp may ever match again.
                for (int i = 0; i < SLOTS; i++) {
                    if (this.stamps[i] != null) {
                        java.util.Arrays.fill(this.stamps[i], 0);
                    }
                }
                this.stampCounter = 1;
            }
            this.slotStamp[s] = this.stampCounter;
            return s;
        }
    }


    /** {@link #nodeDistance} without the depth term (dimension 4). */
    private long partWithoutDepth(int id, long q0, long q1, long q2, long q3, long q5) {
        long[] b = this.bounds;
        int o = id * 12;
        long s = this.offsetTerm[id];
        long t;
        t = distance(b[o], b[o + 1], q0);
        s += t * t;
        t = distance(b[o + 2], b[o + 3], q1);
        s += t * t;
        t = distance(b[o + 4], b[o + 5], q2);
        s += t * t;
        t = distance(b[o + 6], b[o + 7], q3);
        s += t * t;
        t = distance(b[o + 10], b[o + 11], q5);
        s += t * t;
        return s;
    }

    // ---- answers per column without a walk ---------------------------------------------------------------------------------------

    /** {@code -Dgeneratoraccelerator2.climateGroups=false} makes every search walk the tree. */
    public static final boolean GROUPS = !"false".equals(System.getProperty("generatoraccelerator2.climateGroups"));
    private static final Object NO_GROUPS = new Object();
    /** Trees with fewer nodes always walk. */
    private static final int MIN_GROUP_NODES = 128;
    /** Counters: searches answered from the column answers, searches left to the walk (shared distances), column answers filled. */
    public static final java.util.concurrent.atomic.LongAdder GROUP_ANSWERED = new java.util.concurrent.atomic.LongAdder(),
        GROUP_WALKED = new java.util.concurrent.atomic.LongAdder(), GROUP_FILLS = new java.util.concurrent.atomic.LongAdder();
    /** Diagnostics. Searches walked because the tree has no groups, the target is out of the small range, or the column is missing outside a biome fill. */
    public static final java.util.concurrent.atomic.LongAdder WALK_NO_GROUPS = new java.util.concurrent.atomic.LongAdder(),
        WALK_OUT_OF_RANGE = new java.util.concurrent.atomic.LongAdder(), WALK_OUTSIDE = new java.util.concurrent.atomic.LongAdder();
    /** Whether the column answers are currently disabled for this tree. Read only, until the deadline passes. */
    private boolean off() {
        long until = this.offUntil;
        if (until == 0) {
            return false;
        }
        if (System.nanoTime() - until < 0) {
            return true;
        }
        this.offUntil = 0;
        return false;
    }

    /** Times a tree's column answers were turned off for a while (diagnostics). */
    public static final java.util.concurrent.atomic.LongAdder COLUMNS_OFF = new java.util.concurrent.atomic.LongAdder();
    /** Nodes the column fills entered (diagnostics). */
    public static final java.util.concurrent.atomic.LongAdder FILL_NODES = new java.util.concurrent.atomic.LongAdder();

    /**
     * The leaves grouped by their depth range. Depth is the one dimension that changes along a quart column. The other five are fixed
     * for the column. A vanilla tree has four depth ranges. Within a group every leaf has the same depth term. At a given column, the
     * nearest leaf of each group is therefore the one with the smallest distance over the other dimensions, whatever the depth.
     */
    private static final class Groups {
        final int count;
        final long[] lo, hi;
        /** Per node id: the group of a leaf, -1 for an inner node. */
        final int[] leafGroup;
        /** Per node id: one bit per group that has a leaf below the node. */
        final long[] mask;

        Groups(int count, long[] lo, long[] hi, int[] leafGroup, long[] mask) {
            this.count = count;
            this.lo = lo;
            this.hi = hi;
            this.leafGroup = leafGroup;
            this.mask = mask;
        }
    }

    /** {@link Groups}, or {@link #NO_GROUPS} when the tree does not fit: a lone root, more than 64 ranges, or bounds that could overflow. */
    private volatile Object groups;
    /** Diagnostics: columns filled for this tree and searches answered from them. Counted without synchronization, so approximate. */
    public int statFills, statAnswers;
    /**
     * The column answers are disabled for this tree while its columns are rarely asked again. That happens when its searches come at
     * scattered points, or with a climate whose five values change with y. Every {@link #WINDOW} fills, fewer than {@link #MIN_REUSE}
     * answers per fill disables them for {@link #OFF_NANOS}. After that they are tried again. A thread counts a column's answers in its
     * own slot and adds them to the tree when it refills the slot. Searches therefore write nothing shared. The sums are approximate
     * (unsynchronized), which is enough.
     */
    private int windowFills, windowAnswers;
    /** {@link System#nanoTime} until which the column answers are disabled, or 0. */
    private volatile long offUntil;
    private static final int WINDOW = 4096, MIN_REUSE = 4;
    private static final long OFF_NANOS = 2_000_000_000L;
    /** Every flat tree built, weakly held, for the diagnostics. */
    private static final java.util.Set<FlatClimateTree> ALL = java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));

    /** The flat trees made so far (diagnostics). */
    public static java.util.List<FlatClimateTree> all() {
        synchronized (ALL) {
            return new java.util.ArrayList<>(ALL);
        }
    }

    /** The tree's depth groups, or -1 when its searches always walk (diagnostics). */
    public int groupCount() {
        Groups g = groups();
        return g == null ? -1 : g.count;
    }

    /** Mixed into the column slots' hash. */
    private final int salt = System.identityHashCode(this);

    private Groups groups() {
        Object g = this.groups;
        if (g == null) {
            this.groups = g = buildGroups();
        }
        return g instanceof Groups found ? found : null;
    }

    private Object buildGroups() {
        int nodes = this.first.length;
        if (nodes < MIN_GROUP_NODES) {
            return NO_GROUPS; // A walk over a few nodes costs no more than finding the column's answers.
        }
        if (!this.small || this.count[0] == 0) {
            if (!this.small) {
                Ga2Log.get("Biomes").info("climate tree ({} nodes): bounds exceed {} -> tree walk", nodes, SMALL);
            }
            return NO_GROUPS;
        }
        long[] lo = new long[64], hi = new long[64];
        int n = 0;
        int[] leafGroup = new int[nodes];
        long[] mask = new long[nodes];
        for (int id = 0; id < nodes; id++) {
            leafGroup[id] = -1;
            if (this.count[id] != 0) {
                continue;
            }
            long l = this.bounds[id * 12 + 8], h = this.bounds[id * 12 + 9];
            int g = 0;
            while (g < n && (lo[g] != l || hi[g] != h)) {
                g++;
            }
            if (g == n) {
                if (n == 64) {
                    Ga2Log.get("Biomes").info("climate tree ({} nodes): >64 depth ranges -> tree walk", nodes);
                    return NO_GROUPS;
                }
                lo[n] = l;
                hi[n] = h;
                n++;
            }
            leafGroup[id] = g;
        }
        // children have larger ids than their parent (breadth-first)
        for (int id = nodes - 1; id >= 0; id--) {
            if (this.count[id] == 0) {
                mask[id] = 1L << leafGroup[id];
            } else {
                long m = 0;
                for (int c = this.first[id]; c < this.first[id] + this.count[id]; c++) {
                    m |= mask[c];
                }
                mask[id] = m;
            }
        }
        return new Groups(n, java.util.Arrays.copyOf(lo, n), java.util.Arrays.copyOf(hi, n), leafGroup, mask);
    }

    /** One column's nearest leaves per group, over the five dimensions besides the depth, plus the offset. */
    private static final class GroupAnswers {
        FlatClimateTree tree;
        long q0, q1, q2, q3, q5;
        /** Per group: the smallest distance, a leaf at that distance, and whether another leaf of the group has it too. */
        long[] a1 = new long[4];
        int[] id1 = new int[4];
        boolean[] tie1 = new boolean[4];
        /** For Biolith's search ({@link #otherMinima}, with these keys). Same data, over leaves whose biome differs from the group's nearest. */
        int[] otherKeys;
        /** Searches this thread answered from the column since the fill. Added to the tree's counts when the slot is refilled. */
        int answers;
        long[] a2 = new long[4];
        int[] id2 = new int[4];
        boolean[] tie2 = new boolean[4];

        /** Room for {@code n} groups. A slot moves between trees, and most trees have only a few groups. */
        void ensure(int n) {
            if (a1.length < n) {
                a1 = new long[n];
                id1 = new int[n];
                tie1 = new boolean[n];
                a2 = new long[n];
                id2 = new int[n];
                tie2 = new boolean[n];
            }
        }
    }

    /** Per thread: the most recently asked columns (a chunk's 16 and more), and the state used by the walks that fill them. */
    private static final class GroupCache {
        /** Sets of {@link #WAYS} columns each. The least recently asked column is replaced. A chunk's 16 columns are asked in turn. */
        static final int SETS = 64, WAYS = 4;
        final GroupAnswers[] slots = new GroupAnswers[SETS * WAYS];
        /** When each slot was last asked ({@link #clock}). */
        final int[] used = new int[SETS * WAYS];
        int clock;
        /** The stack of the depth-first walks, and the seeds copied from this tree's last column. */
        int[] stack = new int[256];
        final int[] seed1 = new int[64], seed2 = new int[64];
        /** The tree whose other leaves {@link #seed2} holds, and the keys used. Copied when a fill replaced them. */
        FlatClimateTree seed2Tree;
        int[] seed2Keys;
        /** Per tree: the last filled column on this thread. It seeds the bounds of the next fill. */
        final FlatClimateTree[] recentTree = new FlatClimateTree[4];
        final GroupAnswers[] recent = new GroupAnswers[4];
        int recentNext;

        GroupAnswers recent(FlatClimateTree tree) {
            for (int i = 0; i < 4; i++) {
                if (recentTree[i] == tree) {
                    GroupAnswers r = recent[i];
                    return r != null && r.tree == tree ? r : null;
                }
            }
            return null;
        }

        void remember(FlatClimateTree tree, GroupAnswers a) {
            for (int i = 0; i < 4; i++) {
                if (recentTree[i] == tree) {
                    recent[i] = a;
                    return;
                }
            }
            recentTree[recentNext] = tree;
            recent[recentNext] = a;
            recentNext = (recentNext + 1) & 3;
        }

        int[] stack(int size) {
            if (stack.length < size) {
                stack = new int[Math.max(size, stack.length * 2)];
            }
            return stack;
        }

        /** Counts not yet added to {@link #GROUP_ANSWERED} and {@link #GROUP_WALKED}. */
        int answered, walked;
        /** The stack and node parts of the column walks. They live here so that a search does one thread-local lookup. */
        int[] walkStack = new int[64];
        final ColumnParts parts = new ColumnParts();
        /** Biolith's answers of the last {@link #fittestColumns} on this thread. */
        final Fittest fittest = new Fittest();
        /** Inside a chunk's biome fill ({@link #enterFill}). Columns are filled only then. Elsewhere, an uncached column is walked. */
        int inFill;
        int generation;

        /** Releases every tree this cache refers to, and so the biomes of those trees. */
        void forget() {
            java.util.Arrays.fill(slots, null);
            java.util.Arrays.fill(used, 0);
            clock = 0;
            seed2Tree = null;
            seed2Keys = null;
            java.util.Arrays.fill(recentTree, null);
            java.util.Arrays.fill(recent, null);
            recentNext = 0;
            // the column slots keep their arrays for the next world. Without its tree a slot matches no lookup.
            java.util.Arrays.fill(parts.trees, null);
        }

        int[] walkStack(int size) {
            if (walkStack.length < size) {
                walkStack = new int[size];
            }
            return walkStack;
        }

        void count(boolean answer) {
            if (answer) {
                if (++answered == 4096) {
                    GROUP_ANSWERED.add(answered);
                    answered = 0;
                }
            } else if (++walked == 256) {
                GROUP_WALKED.add(walked);
                walked = 0;
            }
        }
    }

    private static final ThreadLocal<GroupCache> GROUP_CACHE = ThreadLocal.withInitial(GroupCache::new);
    /** Bumped when a server stops ({@link #forgetWorlds}). The worker threads outlive the server, so their caches must drop its trees. */
    private static volatile int generation;

    /** This thread's cache. Emptied first if a server stopped since its last use, because the cached trees hold that server's biomes. */
    private static GroupCache groupCache() {
        GroupCache c = GROUP_CACHE.get();
        int g = generation;
        if (c.generation != g) {
            c.forget();
            c.generation = g;
        }
        return c;
    }

    /** A server stopped. Each thread's cache drops its trees the next time that thread uses the cache. */
    public static void forgetWorlds() {
        generation++;
    }

    /** Biolith's two answers ({@link #fittestColumns}). {@code ids} holds the ids ({@code -1} for none), {@code distance} the distances. */
    public static final class Fittest {
        public final int[] ids = new int[2];
        public final long[] distance = new long[2];
    }

    /**
     * Marks this thread as filling a chunk's biomes ({@code ChunkAccess.fillBiomesFromNoise}). That fill asks each quart column once
     * per quart layer. Column answers are built only during a fill. Lookups at scattered points elsewhere, such as structure placement
     * or the spawn search, use an existing column or walk.
     */
    public static void enterFill() {
        groupCache().inFill++;
    }

    public static void exitFill() {
        groupCache().inFill--;
    }

    /** Adds this thread's pending counts to {@link #GROUP_ANSWERED} and {@link #GROUP_WALKED} (tests). */
    static void flushGroupCounts() {
        GroupCache c = groupCache();
        GROUP_ANSWERED.add(c.answered);
        GROUP_WALKED.add(c.walked);
        c.answered = 0;
        c.walked = 0;
    }

    /** Whether some group of {@code mask} still needs leaves at {@code part} or nearer, i.e. the group's bound is not below {@code part}. */
    private static boolean useful(long mask, long part, long[] bound) {
        while (mask != 0) {
            if (part <= bound[Long.numberOfTrailingZeros(mask)]) {
                return true;
            }
            mask &= mask - 1;
        }
        return false;
    }

    /**
     * The column's answers, filled on first use. The fill walks over the five dimensions and enters every node no farther than some
     * group's nearest leaf so far. A node is never nearer than a leaf below it. The walk therefore sees every leaf of a group at the
     * group's final distance. Each group's nearest leaf, and whether that distance is shared, come out exact. Returns null for a
     * column not built yet when outside a chunk's biome fill.
     */
    private GroupAnswers groupAnswers(GroupCache cache, Groups g, long q0, long q1, long q2, long q3, long q5) {
        long h = q0 * 0x9E3779B97F4A7C15L + q1 * 0xC2B2AE3D27D4EB4FL + q2 * 0x165667B19E3779F9L + q3 * 0xD6E8FEB86659FD93L + q5 * 0xFF51AFD7ED558CCDL + this.salt;
        // A mixed hash, so the columns of a chunk and the trees asked there spread over the sets.
        h = (h ^ (h >>> 33)) * 0xFF51AFD7ED558CCDL;
        h = (h ^ (h >>> 33)) * 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        int set = ((int) h & (GroupCache.SETS - 1)) * GroupCache.WAYS;
        GroupAnswers[] slots = cache.slots;
        int now = ++cache.clock;
        int s = set;
        for (int w = set; w < set + GroupCache.WAYS; w++) {
            GroupAnswers a = slots[w];
            if (a == null) {
                s = w;
                break;
            }
            if (a.tree == this && a.q0 == q0 && a.q1 == q1 && a.q2 == q2 && a.q3 == q3 && a.q5 == q5) {
                cache.used[w] = now;
                return a;
            }
            if (now - cache.used[w] > now - cache.used[s]) {
                s = w;
            }
        }
        if (cache.inFill <= 0) {
            return null;
        }
        GroupAnswers a = slots[s];
        // Filled when first asked. A fill costs less than a walk from a cold column. Even a column asked only once is therefore
        // not worse off, e.g. for structure placement, the spawn search, or a climate whose five values change with y. The least
        // recently asked columns make room. A chunk's biome fill keeps asking its own columns, so they stay.
        if (a == null) {
            slots[s] = a = new GroupAnswers();
        }
        cache.used[s] = now;
        GROUP_FILLS.increment();
        if (a.tree != null) {
            a.tree.statAnswers += a.answers;
            a.tree.windowAnswers += a.answers;
        }
        a.answers = 0;
        this.statFills++;
        if (++this.windowFills >= WINDOW) {
            if (this.windowAnswers < MIN_REUSE * this.windowFills) {
                this.offUntil = System.nanoTime() + OFF_NANOS;
                COLUMNS_OFF.increment();
            }
            this.windowFills = 0;
            this.windowAnswers = 0;
        }
        int n = g.count;
        // This tree's last column on this thread. The distances of that column's leaves, measured here, bound each group from the
        // start. A neighbouring column usually has the same nearest leaves. The walk below then enters little more than the nodes
        // at those distances. The previous column is read before the slot is taken over. It may be the slot itself, and until the
        // takeover the slot records which tree its leaves belong to.
        GroupAnswers prev = cache.recent(this);
        int[] seed = cache.seed1;
        for (int i = 0; i < n; i++) {
            seed[i] = prev != null ? prev.id1[i] : -1;
        }
        if (prev != null && prev.otherKeys != null) {
            // Also copy the previous column's other leaves, for this column's Biolith answers.
            System.arraycopy(prev.id2, 0, cache.seed2, 0, n);
            cache.seed2Tree = this;
            cache.seed2Keys = prev.otherKeys;
        }
        a.tree = this;
        a.q0 = q0;
        a.q1 = q1;
        a.q2 = q2;
        a.q3 = q3;
        a.q5 = q5;
        a.otherKeys = null;
        a.ensure(n);
        long[] a1 = a.a1;
        int[] id1 = a.id1;
        boolean[] tie1 = a.tie1;
        for (int i = 0; i < n; i++) {
            int l = seed[i];
            if (l >= this.first.length || l >= 0 && (this.count[l] != 0 || g.leafGroup[l] != i)) {
                l = -1; // Not a leaf of this group in this tree, so no seed.
            }
            a1[i] = l >= 0 ? partWithoutDepth(l, q0, q1, q2, q3, q5) : Long.MAX_VALUE;
            id1[i] = l;
            tie1[i] = false;
        }
        int[] first = this.first, count = this.count;
        long[] mask = g.mask;
        int[] leafGroup = g.leafGroup;
        int[] stack = cache.stack(this.depth * 8 + 8);
        int sp = 0, visited = 0;
        stack[sp++] = 0;
        while (sp > 0) {
            int node = stack[--sp];
            visited++;
            for (int c = first[node], end = c + count[node]; c < end; c++) {
                long p = partWithoutDepth(c, q0, q1, q2, q3, q5);
                if (!useful(mask[c], p, a1)) {
                    continue;
                }
                if (count[c] == 0) {
                    int gi = leafGroup[c];
                    if (p < a1[gi]) {
                        a1[gi] = p;
                        id1[gi] = c;
                        tie1[gi] = false;
                    } else if (c != id1[gi]) {
                        tie1[gi] = true;
                    }
                } else {
                    if (sp == stack.length) {
                        stack = cache.stack(sp * 2);
                    }
                    stack[sp++] = c;
                }
            }
        }
        FILL_NODES.add(visited);
        cache.remember(this, a);
        return a;
    }

    /**
     * Biolith's other answer per group: the nearest leaves whose biome differs from the group's nearest leaf. Groups with a shared
     * nearest distance are skipped, because their answers walk the tree.
     */
    private void otherMinima(GroupCache cache, Groups g, GroupAnswers a, int[] keys) {
        int n = g.count;
        long need = 0;
        for (int i = 0; i < n; i++) {
            a.a2[i] = Long.MAX_VALUE;
            a.id2[i] = -1;
            a.tie2[i] = false;
            if (a.id1[i] >= 0 && !a.tie1[i]) {
                need |= 1L << i;
            }
        }
        a.otherKeys = keys;
        if (need == 0) {
            return;
        }
        long[] a2 = a.a2;
        int[] id2 = a.id2;
        boolean[] tie2 = a.tie2;
        long q0 = a.q0, q1 = a.q1, q2 = a.q2, q3 = a.q3, q5 = a.q5;
        // Seeds, as for the nearest leaves: a recent column's other leaves, where their biome still differs.
        if (cache.seed2Tree == this && cache.seed2Keys == keys) {
            for (long m = need; m != 0; m &= m - 1) {
                int i = Long.numberOfTrailingZeros(m);
                int l = cache.seed2[i];
                if (l >= 0 && l < this.first.length && this.count[l] == 0 && g.leafGroup[l] == i && keys[l] != keys[a.id1[i]]) {
                    a2[i] = partWithoutDepth(l, q0, q1, q2, q3, q5);
                    id2[i] = l;
                }
            }
        }
        int[] first = this.first, count = this.count;
        long[] mask = g.mask;
        int[] leafGroup = g.leafGroup;
        int[] stack = cache.stack(this.depth * 8 + 8);
        int sp = 0, visited = 0;
        stack[sp++] = 0;
        while (sp > 0) {
            int node = stack[--sp];
            visited++;
            for (int c = first[node], end = c + count[node]; c < end; c++) {
                long m = mask[c] & need;
                if (m == 0) {
                    continue;
                }
                long p = partWithoutDepth(c, q0, q1, q2, q3, q5);
                if (!useful(m, p, a2)) {
                    continue;
                }
                if (count[c] == 0) {
                    int gi = leafGroup[c];
                    if (keys[c] == keys[a.id1[gi]]) {
                        continue;
                    }
                    if (p < a2[gi]) {
                        a2[gi] = p;
                        id2[gi] = c;
                        tie2[gi] = false;
                    } else if (c != id2[gi]) {
                        tie2[gi] = true;
                    }
                } else {
                    if (sp == stack.length) {
                        stack = cache.stack(sp * 2);
                    }
                    stack[sp++] = c;
                }
            }
        }
        FILL_NODES.add(visited);
    }

    private static boolean within(long q0, long q1, long q2, long q3, long q4, long q5) {
        return within(q0) && within(q1) && within(q2) && within(q3) && within(q4) && within(q5);
    }

    /**
     * The answer of {@link #search}, taken from the column's group answers, when the nearest leaf is the only leaf at its distance.
     * The walk then ends on that leaf regardless of the last result (see the class comment). Returns -1 when the distance is shared
     * or the column answers do not apply.
     */
    private int groupNearest(GroupCache cache, long q0, long q1, long q2, long q3, long q4, long q5) {
        if (off()) {
            return -1;
        }
        Groups g = GROUPS ? groups() : null;
        if (g == null) {
            WALK_NO_GROUPS.increment();
            return -1;
        }
        if (!within(q0, q1, q2, q3, q4, q5)) {
            WALK_OUT_OF_RANGE.increment();
            return -1;
        }
        GroupAnswers a = groupAnswers(cache, g, q0, q1, q2, q3, q5);
        if (a == null) {
            WALK_OUTSIDE.increment();
            return -1;
        }
        long best = Long.MAX_VALUE;
        int at = -1;
        boolean unique = false;
        for (int i = 0; i < g.count; i++) {
            if (a.id1[i] < 0) {
                continue;
            }
            long t = distance(g.lo[i], g.hi[i], q4);
            long f = a.a1[i] + t * t;
            if (f < best) {
                best = f;
                at = i;
                unique = !a.tie1[i];
            } else if (f == best) {
                unique = false;
            }
        }
        cache.count(unique);
        a.answers++;
        return unique ? a.id1[at] : -1;
    }

    /**
     * The answers of {@link #searchFittest}, taken from the column's group answers, when each answer is the only leaf at its distance.
     * The two answers are the nearest leaf and the nearest leaf of another biome. The walk then ends on them regardless of the last
     * answers (see {@link #searchFittest}). Returns false when either distance is shared or the column answers do not apply.
     */
    private boolean groupFittest(GroupCache cache, long q0, long q1, long q2, long q3, long q4, long q5, int[] keys, int ultimate, int penultimate, int[] ids,
                                 long[] distance) {
        if (off()) {
            return false;
        }
        Groups g = GROUPS ? groups() : null;
        // The walk keeps a last-answer pair of two biomes. A last-answer pair with a single biome is left to the walk.
        if (g == null) {
            WALK_NO_GROUPS.increment();
            return false;
        }
        if (!within(q0, q1, q2, q3, q4, q5) || ultimate >= 0 && penultimate >= 0 && keys[ultimate] == keys[penultimate]) {
            WALK_OUT_OF_RANGE.increment();
            return false;
        }
        GroupAnswers a = groupAnswers(cache, g, q0, q1, q2, q3, q5);
        if (a == null) {
            WALK_OUTSIDE.increment();
            return false;
        }
        if (a.otherKeys != keys) {
            otherMinima(cache, g, a, keys);
        }
        int n = g.count;
        long best = Long.MAX_VALUE;
        int at = -1;
        boolean unique = false;
        for (int i = 0; i < n; i++) {
            if (a.id1[i] < 0) {
                continue;
            }
            long t = distance(g.lo[i], g.hi[i], q4);
            long f = a.a1[i] + t * t;
            if (f < best) {
                best = f;
                at = i;
                unique = !a.tie1[i];
            } else if (f == best) {
                unique = false;
            }
        }
        if (!unique) {
            cache.count(false);
            return false;
        }
        int u = a.id1[at];
        int biome = keys[u];
        long best2 = Long.MAX_VALUE;
        int p = -1;
        boolean unique2 = true;
        for (int i = 0; i < n; i++) {
            if (a.id1[i] < 0) {
                continue;
            }
            long cand;
            int id;
            boolean shared;
            if (a.tie1[i]) {
                // The biomes sharing the group's nearest distance are unknown. If this group decides, the walk answers.
                cand = a.a1[i];
                id = -1;
                shared = true;
            } else if (keys[a.id1[i]] != biome) {
                cand = a.a1[i];
                id = a.id1[i];
                shared = false;
            } else {
                if (a.id2[i] < 0) {
                    continue;
                }
                cand = a.a2[i];
                id = a.id2[i];
                shared = a.tie2[i];
            }
            long t = distance(g.lo[i], g.hi[i], q4);
            long f = cand + t * t;
            if (f < best2) {
                best2 = f;
                p = id;
                unique2 = !shared;
            } else if (f == best2) {
                unique2 = false;
            }
        }
        cache.count(unique2);
        a.answers++;
        if (!unique2) {
            return false;
        }
        ids[0] = u;
        ids[1] = p;
        distance[0] = best;
        distance[1] = best2;
        return true;
    }

    /** {@code -Dgeneratoraccelerator2.climateColumns=false} keeps every search computing all terms of every node it visits. */
    public static final boolean COLUMNS = !"false".equals(System.getProperty("generatoraccelerator2.climateColumns"));

    /**
     * {@link #search} with each node's non-depth terms taken from the thread's column cache ({@link ColumnParts}). The walk,
     * the strict comparisons and the answer are the same (see {@link #searchFittestColumns}).
     */
    public int searchColumns(long q0, long q1, long q2, long q3, long q4, long q5, int last) {
        if (this.count[0] == 0) {
            return 0;
        }
        GroupCache c = groupCache();
        int nearest = groupNearest(c, q0, q1, q2, q3, q4, q5);
        return nearest >= 0 ? nearest : walkColumns(c, q0, q1, q2, q3, q4, q5, last);
    }

    /** {@link #searchColumns} without the column answers, i.e. the walk with the column parts. Tests compare the two. */
    int walkColumns(long q0, long q1, long q2, long q3, long q4, long q5, int last) {
        return walkColumns(groupCache(), q0, q1, q2, q3, q4, q5, last);
    }

    private int walkColumns(GroupCache c, long q0, long q1, long q2, long q3, long q4, long q5, int last) {
        if (this.count[0] == 0) {
            return 0;
        }
        int best = last;
        long bestDistance = last < 0 ? Long.MAX_VALUE : nodeDistance(last, q0, q1, q2, q3, q4, q5);
        int[] stack = c.walkStack(2 * (this.depth + 1));
        ColumnParts cache = c.parts;
        int slot = cache.slot(this, q0, q1, q2, q3, q5);
        long[] parts = cache.parts[slot];
        int[] stamps = cache.stamps[slot];
        int stamp = cache.slotStamp[slot];
        long[] b = this.bounds;
        int[] first = this.first;
        int[] count = this.count;
        boolean bounded = this.small && within(q0) && within(q1) && within(q2) && within(q3) && within(q4) && within(q5);
        int sp = 0;
        stack[0] = 0;
        stack[1] = first[0];
        while (sp >= 0) {
            int node = stack[2 * sp];
            int child = stack[2 * sp + 1];
            if (child == first[node] + count[node]) {
                sp--;
                continue;
            }
            stack[2 * sp + 1] = child + 1;
            long part;
            if (stamps[child] == stamp) {
                part = parts[child];
            } else {
                part = partWithoutDepth(child, q0, q1, q2, q3, q5);
                parts[child] = part;
                stamps[child] = stamp;
            }
            long d;
            if (bounded && part >= bestDistance) {
                d = part;
            } else {
                long t = distance(b[child * 12 + 8], b[child * 12 + 9], q4);
                d = part + t * t;
            }
            if (bestDistance > d) {
                if (count[child] == 0) {
                    bestDistance = d;
                    best = child;
                } else {
                    sp++;
                    stack[2 * sp] = child;
                    stack[2 * sp + 1] = first[child];
                }
            }
        }
        return best;
    }

    /**
     * {@link #searchFittest} with each node's non-depth terms taken from the thread's column cache ({@link ColumnParts}). The
     * walk, the strict comparisons and the answers are the same. A node's distance is the same sum, because wrapping addition
     * does not depend on order. With bounded inputs, a cached part at or over the limit decides the comparison as the full sum
     * would.
     */
    public void searchFittestColumns(long q0, long q1, long q2, long q3, long q4, long q5, int[] keys, int ultimate, int penultimate, int[] ids, long[] distance) {
        GroupCache c = groupCache();
        if (!groupFittest(c, q0, q1, q2, q3, q4, q5, keys, ultimate, penultimate, ids, distance)) {
            walkFittestColumns(c, q0, q1, q2, q3, q4, q5, keys, ultimate, penultimate, ids, distance);
        }
    }

    /** {@link #searchFittestColumns} into the thread's own result ({@link Fittest}). The result is valid until the thread's next search. */
    public Fittest fittestColumns(long q0, long q1, long q2, long q3, long q4, long q5, int[] keys, int ultimate, int penultimate) {
        GroupCache c = groupCache();
        Fittest r = c.fittest;
        if (!groupFittest(c, q0, q1, q2, q3, q4, q5, keys, ultimate, penultimate, r.ids, r.distance)) {
            walkFittestColumns(c, q0, q1, q2, q3, q4, q5, keys, ultimate, penultimate, r.ids, r.distance);
        }
        return r;
    }

    /** {@link #searchFittestColumns} without the column answers (tests compare the two). */
    void walkFittestColumns(long q0, long q1, long q2, long q3, long q4, long q5, int[] keys, int ultimate, int penultimate, int[] ids, long[] distance) {
        walkFittestColumns(groupCache(), q0, q1, q2, q3, q4, q5, keys, ultimate, penultimate, ids, distance);
    }

    private void walkFittestColumns(GroupCache c, long q0, long q1, long q2, long q3, long q4, long q5, int[] keys, int ultimate, int penultimate, int[] ids,
                                    long[] distance) {
        int none = this.first.length;
        int u = ultimate;
        int p = penultimate;
        long du = u < 0 ? Long.MAX_VALUE : nodeDistance(u, q0, q1, q2, q3, q4, q5);
        long dp = p < 0 ? Long.MAX_VALUE : nodeDistance(p, q0, q1, q2, q3, q4, q5);
        if (du > dp) {
            int t = u;
            u = p;
            p = t;
            long td = du;
            du = dp;
            dp = td;
        }
        int[] stack = c.walkStack(2 * (this.depth + 1));
        ColumnParts cache = c.parts;
        int slot = cache.slot(this, q0, q1, q2, q3, q5);
        long[] parts = cache.parts[slot];
        int[] stamps = cache.stamps[slot];
        int stamp = cache.slotStamp[slot];
        long[] b = this.bounds;
        int[] first = this.first;
        int[] count = this.count;
        boolean bounded = this.small && within(q0) && within(q1) && within(q2) && within(q3) && within(q4) && within(q5);
        int sp = 0;
        stack[0] = 0;
        stack[1] = first[0];
        while (sp >= 0) {
            int node = stack[2 * sp];
            int child = stack[2 * sp + 1];
            if (child == first[node] + count[node]) {
                sp--;
                continue;
            }
            stack[2 * sp + 1] = child + 1;
            long part;
            if (stamps[child] == stamp) {
                part = parts[child];
            } else {
                part = partWithoutDepth(child, q0, q1, q2, q3, q5);
                parts[child] = part;
                stamps[child] = stamp;
            }
            long d;
            if (bounded && part >= dp) {
                d = part;
            } else {
                long t = distance(b[child * 12 + 8], b[child * 12 + 9], q4);
                d = part + t * t;
            }
            if (dp > d) {
                if (count[child] == 0) {
                    boolean otherBiome = keys[child] != keys[u < 0 ? none : u];
                    if (du > d) {
                        if (otherBiome) {
                            p = u;
                            dp = du;
                        }
                        u = child;
                        du = d;
                    } else if (otherBiome) {
                        p = child;
                        dp = d;
                    }
                } else {
                    sp++;
                    stack[2 * sp] = child;
                    stack[2 * sp + 1] = first[child];
                }
            }
        }
        ids[0] = u;
        ids[1] = p;
        distance[0] = du;
        distance[1] = dp;
    }

    /** Number of node ids (leaf ids are below it). */
    public int nodes() {
        return this.first.length;
    }

    /** True when the root is a subtree. The walks above need one. A lone leaf or an empty tree does not qualify. */
    public boolean hasSubtrees() {
        return this.count[0] != 0;
    }

    public Climate.RTree.Leaf<?> leaf(int id) {
        return this.leaves[id];
    }

    /** Stores a leaf's id in the leaf, so the thread's last result maps back to an id without a lookup. */
    @FunctionalInterface
    public interface LeafIndexer {
        void index(Climate.RTree.Leaf<?> leaf, int id);
    }
}
