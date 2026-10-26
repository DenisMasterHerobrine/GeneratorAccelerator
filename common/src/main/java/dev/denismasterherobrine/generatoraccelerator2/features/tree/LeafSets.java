package dev.denismasterherobrine.generatoraccelerator2.features.tree;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * The seven distance sets of {@code TreeFeature.updateLeaves}. Vanilla keeps them as {@code HashSet<BlockPos>}s and takes the
 * first element again and again. These sets keep "the first element" equal to the one {@code HashSet} would give,
 * without its cost. A {@code HashSet} iterator finds its first element by scanning the table from bucket 0, so vanilla's loop
 * rescans the emptied low buckets on every step.
 *
 * <p>Without tree bins, a {@code HashMap} iterates its buckets in ascending order. A key's bucket is its hash {@code h ^ h >>> 16}
 * masked by the capacity. Within a bucket, the keys come in the order they were added. A key is appended at its chain's tail, and
 * a resize splits every chain keeping its order. The capacity follows the same rules here: 16 on the first add, doubling past 3/4
 * full, and doubling instead of turning a 9-key bin into a tree while the table is under 64. A set whose bin would become a tree
 * (9 keys, a table of 64 or more) is handed to a real {@code HashSet} that has been through the same adds and takes. That gives
 * the same table, including any longer chain a split left as a list. From then on the JDK's own code decides. In a tree bin the
 * JDK orders keys of equal hash code by identity hash. Two positions 31 blocks apart in y and one in z share
 * {@code BlockPos.hashCode}. So for a tree over 31 blocks tall whose set reaches a tree bin holding such a pair, vanilla's own
 * order depends on which objects it allocated and is not repeatable. The handed-over set then gives one of the orders vanilla can
 * give. {@link #VERIFIED} compares all of this against the running JDK's {@code HashSet} and {@code BlockPos.hashCode} once. If
 * anything differs, trees keep vanilla's sets.
 */
final class LeafSets {
    static final int LEVELS = 7;
    private static final ThreadLocal<LeafSets> LOCAL = ThreadLocal.withInitial(LeafSets::new);
    static final boolean VERIFIED = check(20261010L, 48, 600, null) == null;
    /** Verified, and not switched off ({@code -Dgeneratoraccelerator2.leafSets=false}). */
    static final boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.leafSets")) && VERIFIED;

    // the box: positions are kept by their index in it
    private int minX, minY, minZ, sizeX, sizeZ;
    /** Bit k: the position is in (emulated) set k. */
    private byte[] member;

    // chain nodes, shared by the sets. Never reused within one run.
    private int[] next, hash, cell;
    private int nodes;

    private final int[] cap = new int[LEVELS];
    private final int[] size = new int[LEVELS];
    /** No non-empty bucket lies below this one. */
    private final int[] low = new int[LEVELS];
    private final int[][] head = new int[LEVELS][];
    private final int[][] tail = new int[LEVELS][];
    /** A bit per non-empty bucket. */
    private final long[][] used = new long[LEVELS][];
    @SuppressWarnings("unchecked")
    private final HashSet<BlockPos>[] real = new HashSet[LEVELS];
    /** Each set's history: the cell of every add that changed it, -1 for every take. */
    private final int[][] history = new int[LEVELS][];
    private final int[] historyLength = new int[LEVELS];

    /** The position {@link #poll} took. */
    int x, y, z;

    private LeafSets() {
        trim();
    }

    static LeafSets get() {
        return LOCAL.get();
    }

    /** Above this box size the buffers go back to their first sizes before a much smaller tree. */
    private static final int TRIM_BYTES = 1 << 20;

    /** The buffers at their first sizes. Called only between trees. */
    private void trim() {
        member = new byte[4096];
        next = new int[1024];
        hash = new int[1024];
        cell = new int[1024];
        for (int i = 0; i < LEVELS; i++) {
            head[i] = new int[16];
            tail[i] = new int[16];
            used[i] = new long[1];
            history[i] = new int[256];
        }
    }

    /**
     * Empty sets over {@code box}, then {@code levels.get(0).addAll(logs)}. False when the box holds more than {@code 1 << 26}
     * blocks, or when a log lies outside the box. The latter is not expected, since the box encloses the logs. The caller then
     * keeps vanilla's sets.
     */
    boolean begin(BoundingBox box, Set<BlockPos> logs) {
        minX = box.minX();
        minY = box.minY();
        minZ = box.minZ();
        sizeX = box.getXSpan();
        sizeZ = box.getZSpan();
        long volume = (long) sizeX * box.getYSpan() * sizeZ;
        if (volume > 1 << 26) {
            return false;
        }
        if (member.length > TRIM_BYTES && volume * 4 < member.length) {
            // One huge tree grew the buffers. The thread does not keep that size for every tree after it.
            trim();
        }
        if (member.length < volume) {
            member = new byte[(int) Math.max(volume, member.length * 2L)];
        } else {
            Arrays.fill(member, 0, (int) volume, (byte) 0);
        }
        nodes = 0;
        Arrays.fill(cap, 0);
        Arrays.fill(size, 0);
        Arrays.fill(real, null);
        Arrays.fill(historyLength, 0);
        for (BlockPos pos : logs) {
            if (!box.isInside(pos)) {
                return false;
            }
        }
        for (BlockPos pos : logs) {
            add(0, pos.getX(), pos.getY(), pos.getZ());
        }
        return true;
    }

    boolean isEmpty(int level) {
        HashSet<BlockPos> set = real[level];
        return set != null ? set.isEmpty() : size[level] == 0;
    }

    /** {@code levels.get(level).add(new BlockPos(x, y, z))}, for a position inside the box. */
    void add(int level, int x, int y, int z) {
        HashSet<BlockPos> set = real[level];
        if (set != null) {
            set.add(new BlockPos(x, y, z));
            return;
        }
        int c = (x - minX) + sizeX * ((z - minZ) + sizeZ * (y - minY));
        int bit = 1 << level;
        if ((member[c] & bit) != 0) {
            return;
        }
        int h = (y + z * 31) * 31 + x;
        h ^= h >>> 16;
        if (cap[level] == 0) {
            open(level);
        }
        int[] hd = head[level];
        int b = h & (cap[level] - 1);
        int first = hd[b];
        boolean treeBin = false;
        if (first >= 0) {
            int len = 1;
            for (int e = next[first]; e >= 0; e = next[e]) {
                len++;
            }
            if (len >= 8) {
                if (cap[level] >= 64) {
                    // the JDK turns this bin into a tree. From here on a real HashSet holds the set.
                    handOver(level);
                    real[level].add(new BlockPos(x, y, z));
                    return;
                }
                treeBin = true;
            }
        }
        int n = node(h, c);
        if (first < 0) {
            hd[b] = n;
            used[level][b >>> 6] |= 1L << b;
            if (b < low[level]) {
                low[level] = b;
            }
        } else {
            next[tail[level][b]] = n;
        }
        tail[level][b] = n;
        member[c] |= (byte) bit;
        record(level, c);
        if (treeBin) {
            // a table under 64 doubles instead
            resize(level);
        }
        if (++size[level] > cap[level] / 4 * 3) {
            resize(level);
        }
    }

    /** {@code Iterator i = levels.get(level).iterator(); pos = i.next(); i.remove();} into {@link #x}, {@link #y}, {@link #z}. */
    void poll(int level) {
        HashSet<BlockPos> set = real[level];
        if (set != null) {
            Iterator<BlockPos> it = set.iterator();
            BlockPos pos = it.next();
            it.remove();
            x = pos.getX();
            y = pos.getY();
            z = pos.getZ();
            return;
        }
        long[] u = used[level];
        int w = low[level] >>> 6;
        while (u[w] == 0) {
            w++;
        }
        int b = (w << 6) + Long.numberOfTrailingZeros(u[w]);
        low[level] = b;
        int[] hd = head[level];
        int n = hd[b];
        int after = next[n];
        hd[b] = after;
        if (after < 0) {
            u[w] &= ~(1L << b);
        }
        size[level]--;
        record(level, -1);
        int c = cell[n];
        member[c] &= (byte) ~(1 << level);
        x = minX + c % sizeX;
        int rest = c / sizeX;
        z = minZ + rest % sizeZ;
        y = minY + rest / sizeZ;
    }

    private int node(int h, int c) {
        int n = nodes++;
        if (n == next.length) {
            next = Arrays.copyOf(next, n * 2);
            hash = Arrays.copyOf(hash, n * 2);
            cell = Arrays.copyOf(cell, n * 2);
        }
        next[n] = -1;
        hash[n] = h;
        cell[n] = c;
        return n;
    }

    /** The JDK's first put: a table of 16. */
    private void open(int level) {
        cap[level] = 16;
        Arrays.fill(head[level], 0, 16, -1);
        used[level][0] = 0;
        low[level] = 0;
    }

    /** The JDK's resize: double the table, splitting every chain in order between bucket j and j + old capacity. */
    private void resize(int level) {
        int old = cap[level];
        int cap2 = old * 2;
        if (head[level].length < cap2) {
            head[level] = Arrays.copyOf(head[level], cap2);
            tail[level] = Arrays.copyOf(tail[level], cap2);
        }
        int[] hd = head[level];
        int[] tl = tail[level];
        int words = (cap2 + 63) >>> 6;
        if (used[level].length < words) {
            used[level] = new long[words];
        }
        long[] u = used[level];
        Arrays.fill(u, 0, words, 0L);
        for (int j = 0; j < old; j++) {
            int e = hd[j];
            int loHead = -1, loTail = -1, hiHead = -1, hiTail = -1;
            while (e >= 0) {
                int after = next[e];
                if ((hash[e] & old) == 0) {
                    if (loTail < 0) {
                        loHead = e;
                    } else {
                        next[loTail] = e;
                    }
                    loTail = e;
                } else {
                    if (hiTail < 0) {
                        hiHead = e;
                    } else {
                        next[hiTail] = e;
                    }
                    hiTail = e;
                }
                e = after;
            }
            if (loTail >= 0) {
                next[loTail] = -1;
                u[j >>> 6] |= 1L << j;
            }
            if (hiTail >= 0) {
                next[hiTail] = -1;
                int k = j + old;
                u[k >>> 6] |= 1L << k;
            }
            hd[j] = loHead;
            tl[j] = loTail;
            hd[j + old] = hiHead;
            tl[j + old] = hiTail;
        }
        cap[level] = cap2;
        low[level] = 0;
    }

    private void record(int level, int c) {
        int n = historyLength[level];
        if (n == history[level].length) {
            history[level] = Arrays.copyOf(history[level], n * 2);
        }
        history[level][n] = c;
        historyLength[level] = n + 1;
    }

    /**
     * A real HashSet put through this set's history, so its table is the one vanilla's set has now. Adding the chains in order
     * to a table of the same size is not enough. A split can leave a chain of nine or more as a list, and re-adding would turn
     * that chain into a tree bin.
     */
    private void handOver(int level) {
        HashSet<BlockPos> set = new HashSet<>();
        int[] h = history[level];
        for (int i = 0, n = historyLength[level]; i < n; i++) {
            int c = h[i];
            if (c >= 0) {
                int rest = c / sizeX;
                set.add(new BlockPos(minX + c % sizeX, minY + rest / sizeZ, minZ + rest % sizeZ));
            } else {
                Iterator<BlockPos> it = set.iterator();
                it.next();
                it.remove();
            }
        }
        real[level] = set;
    }

    /**
     * These sets against {@code HashSet<BlockPos>}: {@code runs} random runs of {@code ops} adds and takes over all seven
     * sets, in boxes near the origin (where bins collide enough to reach tree bins) and far out. Null when every take matched.
     * {@code handedOver[0]}, when given, counts the sets that went to a real HashSet.
     */
    static String check(long seed, int runs, int ops, int[] handedOver) {
        Random random = new Random(seed);
        LeafSets sets = new LeafSets();
        for (int run = 0; run < runs; run++) {
            boolean near = (run & 1) == 0;
            int x0 = near ? random.nextInt(40) - 20 : random.nextInt(60_000_000) - 30_000_000;
            int z0 = near ? random.nextInt(40) - 20 : random.nextInt(60_000_000) - 30_000_000;
            int y0 = random.nextInt(300) - 64;
            // under 31 tall: no two positions of a box share a hash code, so the JDK's tree bins order them repeatably
            int sx = 3 + random.nextInt(22), sy = 3 + random.nextInt(28), sz = 3 + random.nextInt(22);
            BoundingBox box = new BoundingBox(x0, y0, z0, x0 + sx - 1, y0 + sy - 1, z0 + sz - 1);
            Set<BlockPos> logs = new HashSet<>();
            int nLogs = random.nextInt(Math.min(40, sx * sy * sz));
            for (int i = 0; i < nLogs; i++) {
                logs.add(new BlockPos(x0 + random.nextInt(sx), y0 + random.nextInt(sy), z0 + random.nextInt(sz)));
            }
            @SuppressWarnings("unchecked")
            Set<BlockPos>[] expected = new Set[LEVELS];
            for (int i = 0; i < LEVELS; i++) {
                expected[i] = new HashSet<>();
            }
            expected[0].addAll(logs);
            if (!sets.begin(box, logs)) {
                return "run " + run + ": begin refused";
            }
            // anti-diagonals at one height share a bucket near the origin. Weight them so long chains form.
            int diagonal = random.nextInt(sx + sz), dy = random.nextInt(sy);
            for (int op = 0; op < ops; op++) {
                int level = random.nextInt(LEVELS);
                if (random.nextInt(10) < 7 || expected[level].isEmpty()) {
                    int x, y, z;
                    if (near && random.nextInt(3) == 0) {
                        x = x0 + random.nextInt(sx);
                        z = z0 + Math.floorMod(diagonal - (x - x0), sz);
                        y = y0 + dy;
                    } else {
                        x = x0 + random.nextInt(sx);
                        y = y0 + random.nextInt(sy);
                        z = z0 + random.nextInt(sz);
                    }
                    expected[level].add(new BlockPos(x, y, z));
                    sets.add(level, x, y, z);
                } else {
                    Iterator<BlockPos> it = expected[level].iterator();
                    BlockPos want = it.next();
                    it.remove();
                    sets.poll(level);
                    if (want.getX() != sets.x || want.getY() != sets.y || want.getZ() != sets.z) {
                        return "run " + run + " op " + op + " set " + level + ": expected " + want + ", got " + sets.x + "," + sets.y + "," + sets.z;
                    }
                }
                if (expected[level].isEmpty() != sets.isEmpty(level)) {
                    return "run " + run + " op " + op + " set " + level + ": emptiness differs";
                }
            }
            if (handedOver != null) {
                handedOver[0] += sets.handedOver();
            }
            for (int level = 0; level < LEVELS; level++) {
                Iterator<BlockPos> it = expected[level].iterator();
                while (it.hasNext()) {
                    BlockPos want = it.next();
                    it.remove();
                    sets.poll(level);
                    if (want.getX() != sets.x || want.getY() != sets.y || want.getZ() != sets.z) {
                        return "run " + run + " drain set " + level + ": expected " + want + ", got " + sets.x + "," + sets.y + "," + sets.z;
                    }
                }
                if (!sets.isEmpty(level)) {
                    return "run " + run + " drain set " + level + ": not empty";
                }
            }
        }
        return null;
    }

    /** How many of the last run's sets went to a real HashSet (tests). */
    int handedOver() {
        int n = 0;
        for (HashSet<BlockPos> s : real) {
            if (s != null) {
                n++;
            }
        }
        return n;
    }
}
