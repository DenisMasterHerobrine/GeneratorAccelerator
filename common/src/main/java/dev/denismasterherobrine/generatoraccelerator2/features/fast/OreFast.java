package dev.denismasterherobrine.generatoraccelerator2.features.fast;

import dev.denismasterherobrine.generatoraccelerator2.features.FeatureStats;
import dev.denismasterherobrine.generatoraccelerator2.features.region.Ga2Region;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;

/**
 * Transcription of {@code OreFeature.doPlace}: same spheres, same sphere/x/y/z visit order, same
 * per-target loop with the same random calls, same unlocked section writes, same section
 * acquisition as {@code BulkSectionAccess} (once per section, released at the end), and the same
 * "air outside the build height" neighbour rule. Cheaper because the chunk is resolved once per
 * section, the build height and write radius are plain integer compares, and the rule tests of a
 * configuration are answered from a per-block-state table.
 */
public final class OreFast {
    private OreFast() {}

    /** Blocks already visited, indexed like vanilla's BitSet (including its overlap when a sphere reaches width/height past the box start). */
    private static final ThreadLocal<long[]> VISITED = ThreadLocal.withInitial(() -> new long[256]);
    private static final ThreadLocal<double[]> SQ_Y = ThreadLocal.withInitial(() -> new double[16]);
    private static final ThreadLocal<double[]> SPHERES = ThreadLocal.withInitial(() -> new double[256]);
    private static final ThreadLocal<double[]> SQ_Z = ThreadLocal.withInitial(() -> new double[16]);

    /** Mirrors {@code BulkSectionAccess} on top of the region's chunk cache (one per thread, {@link #start}ed per placement). */
    private static final class Sections {
        private Ga2Region region;
        private int minSection;
        private int sectionCount;
        private LevelChunkSection[] acquired = new LevelChunkSection[8];
        private int acquiredCount;
        private ChunkAccess chunk;
        private int chunkX = Integer.MIN_VALUE;
        private int chunkZ = Integer.MIN_VALUE;
        private LevelChunkSection last;
        private int lastKeyX = Integer.MIN_VALUE;
        private int lastKeyY;
        private int lastKeyZ;

        /** As a new instance for {@code region}: nothing acquired, no chunk or section remembered. */
        Sections start(Ga2Region region) {
            this.region = region;
            this.minSection = region.ga2$minY() >> 4;
            this.sectionCount = (region.ga2$maxY() >> 4) - minSection;
            this.acquiredCount = 0;
            this.chunk = null;
            this.chunkX = Integer.MIN_VALUE;
            this.chunkZ = Integer.MIN_VALUE;
            this.last = null;
            this.lastKeyX = Integer.MIN_VALUE;
            return this;
        }

        /** {@code BulkSectionAccess.getSection}: null outside the build height, otherwise the (acquired) section. */
        LevelChunkSection section(int x, int y, int z) {
            int idx = (y >> 4) - minSection;
            if (idx < 0 || idx >= sectionCount) {
                return null;
            }
            int sx = x >> 4;
            int sy = y >> 4;
            int sz = z >> 4;
            if (last != null && lastKeyX == sx && lastKeyY == sy && lastKeyZ == sz) {
                return last;
            }
            if (sx != chunkX || sz != chunkZ) {
                chunk = region.ga2$chunk(sx, sz);
                chunkX = sx;
                chunkZ = sz;
            }
            LevelChunkSection s = chunk.getSection(idx);
            boolean seen = false;
            for (int i = 0; i < acquiredCount; i++) {
                if (acquired[i] == s) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                s.acquire();
                if (acquiredCount == acquired.length) {
                    acquired = java.util.Arrays.copyOf(acquired, acquiredCount * 2);
                }
                acquired[acquiredCount++] = s;
            }
            last = s;
            lastKeyX = sx;
            lastKeyY = sy;
            lastKeyZ = sz;
            return s;
        }

        /** {@code BulkSectionAccess.getBlockState}: AIR outside the build height. */
        BlockState state(int x, int y, int z) {
            LevelChunkSection s = section(x, y, z);
            return s == null ? Blocks.AIR.defaultBlockState() : dev.denismasterherobrine.generatoraccelerator2.chunkio.StateReads.get(s, x & 15, y & 15, z & 15);
        }

        void close() {
            for (int i = 0; i < acquiredCount; i++) {
                acquired[i].release();
                acquired[i] = null;
            }
            acquiredCount = 0;
            region = null;
            chunk = null;
            last = null;
        }
    }

    /** Exact {@code OreFeature.place}: the three random draws, the ocean-floor gate over the blob's columns (worldgen heightmap, read through the cursor), then {@link #doPlace}. */
    public static boolean place(net.minecraft.world.level.levelgen.feature.FeaturePlaceContext<?> ctx, Ga2Region region) {
        RandomSource random = ctx.random();
        BlockPos origin = ctx.origin();
        OreConfiguration config = (OreConfiguration) ctx.config();
        float f = random.nextFloat() * (float) Math.PI;
        float f1 = (float) config.size / 8.0F;
        int i = Mth.ceil(((float) config.size / 16.0F * 2.0F + 1.0F) / 2.0F);
        double d0 = (double) origin.getX() + Math.sin((double) f) * (double) f1;
        double d1 = (double) origin.getX() - Math.sin((double) f) * (double) f1;
        double d2 = (double) origin.getZ() + Math.cos((double) f) * (double) f1;
        double d3 = (double) origin.getZ() - Math.cos((double) f) * (double) f1;
        double d4 = (double) (origin.getY() + random.nextInt(3) - 2);
        double d5 = (double) (origin.getY() + random.nextInt(3) - 2);
        int k = origin.getX() - Mth.ceil(f1) - i;
        int l = origin.getY() - 2 - i;
        int i1 = origin.getZ() - Mth.ceil(f1) - i;
        int j1 = 2 * (Mth.ceil(f1) + i);
        int k1 = 2 * (2 + i);
        // a blob above every column of its box (upper ores, high in the air) fails the gate below on every column. The highest
        // column of each chunk the box covers answers that at once. The gate reads nothing else and changes nothing.
        dev.denismasterherobrine.generatoraccelerator2.features.region.RegionReads reads = region.ga2$reads();
        if (reads != null) {
            int highest = Integer.MIN_VALUE;
            boolean known = true;
            for (int qx = k >> 4; known && qx <= (k + j1) >> 4; qx++) {
                for (int qz = i1 >> 4; qz <= (i1 + j1) >> 4; qz++) {
                    int m = reads.oceanFloorMax(qx, qz);
                    if (m == dev.denismasterherobrine.generatoraccelerator2.features.region.RegionReads.UNKNOWN) {
                        known = false;
                        break;
                    }
                    highest = Math.max(highest, m);
                }
            }
            if (known && l > highest) {
                FeatureStats.fast(OreFeature.class);
                return false;
            }
        }
        // the thread's cursor. The gate only reads heightmaps, and is done before doPlace.
        dev.denismasterherobrine.generatoraccelerator2.features.region.SectionCursor cursor = CELLS.get().ownCursor.reset(region);
        boolean gate = false;
        search:
        for (int l1 = k; l1 <= k + j1; l1++) {
            for (int i2 = i1; i2 <= i1 + j1; i2++) {
                if (l <= cursor.height(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG, l1, i2)) {
                    gate = true;
                    break search;
                }
            }
        }
        cursor.clear();
        if (gate) {
            return doPlace(region, random, config, d0, d1, d2, d3, d4, d5, k, l, i1, j1, k1);
        }
        FeatureStats.fast(OreFeature.class);
        return false;
    }

    /** Exact {@code OreFeature.doPlace}. */
    public static boolean doPlace(Ga2Region region, RandomSource random, OreConfiguration config, double minX, double maxX, double minZ, double maxZ,
                                  double minY, double maxY, int x, int y, int z, int width, int height) {
        OreTargets targets = OreTargets.of(config);
        int placed = 0;
        int size = config.size;
        // every slot below size * 4 is written before it is read
        double[] spheres = SPHERES.get();
        if (spheres.length < size * 4) {
            spheres = new double[size * 4];
            SPHERES.set(spheres);
        }
        for (int k = 0; k < size; k++) {
            float f = (float) k / (float) size;
            double d0 = Mth.lerp((double) f, minX, maxX);
            double d1 = Mth.lerp((double) f, minY, maxY);
            double d2 = Mth.lerp((double) f, minZ, maxZ);
            double d3 = random.nextDouble() * (double) size / 16.0;
            double d4 = ((double) (Mth.sin((float) Math.PI * f) + 1.0F) * d3 + 1.0) / 2.0;
            spheres[k * 4 + 0] = d0;
            spheres[k * 4 + 1] = d1;
            spheres[k * 4 + 2] = d2;
            spheres[k * 4 + 3] = d4;
        }
        // a sphere can only hold another when the gap of their radii (under size / 16) exceeds the distance of their centres.
        // The centres step along a segment of length 2 * size / 8 in size steps (a quarter block each, more with the y slope).
        // So past 0.27 * size + 2 steps the test is false, and vanilla's loop changes nothing. That reach is 8% over the bound,
        // far above the rounding.
        int reach = (int) (0.27 * size) + 2;
        for (int a = 0; a < size - 1; a++) {
            if (!(spheres[a * 4 + 3] <= 0.0)) {
                int end = Math.min(size, a + reach + 1);
                for (int b = a + 1; b < end; b++) {
                    if (!(spheres[b * 4 + 3] <= 0.0)) {
                        double d8 = spheres[a * 4 + 0] - spheres[b * 4 + 0];
                        double d10 = spheres[a * 4 + 1] - spheres[b * 4 + 1];
                        double d12 = spheres[a * 4 + 2] - spheres[b * 4 + 2];
                        double d14 = spheres[a * 4 + 3] - spheres[b * 4 + 3];
                        if (d14 * d14 > d8 * d8 + d10 * d10 + d12 * d12) {
                            if (d14 > 0.0) {
                                spheres[b * 4 + 3] = -1.0;
                            } else {
                                spheres[a * 4 + 3] = -1.0;
                            }
                        }
                    }
                }
            }
        }
        Cells cells = CELLS.get();
        // the thread's own instances. An ore placement never places another one inside it.
        BlockPos.MutableBlockPos mutable = cells.ownMutable;
        Sections sections = cells.ownSections.start(region);
        cells.start(region, random, config, targets, sections, mutable);
        double[] ysq = SQ_Y.get();
        double[] zsq = SQ_Z.get();
        int buildMin = region.ga2$minY();
        int buildMax = region.ga2$maxY();
        // vanilla's BitSet index: x + y * width + z * width * height from the box start. When no sphere reaches width (x) or height
        // (y) past the box start, the index is one bit per block. A block's first visit is then the first time the z run of its
        // (x, y) row covers it. A 64-bit mask per row then does what the BitSet does, a row at a time. Spheres overlap their
        // neighbours most of the way.
        boolean byRows = fitsRows(width, height, x, y, z, spheres, size);
        long[] rows = byRows ? rows(width * height) : null;
        long[] visited = byRows ? null : visited(width, height, x, y, z, spheres, size);
        int wh = width * height;
        try {
            for (int s = 0; s < size; s++) {
                double radius = spheres[s * 4 + 3];
                if (!(radius < 0.0)) {
                    double cx = spheres[s * 4 + 0];
                    double cy = spheres[s * 4 + 1];
                    double cz = spheres[s * 4 + 2];
                    int x0 = Math.max(Mth.floor(cx - radius), x);
                    int y0 = Math.max(Mth.floor(cy - radius), y);
                    int z0 = Math.max(Mth.floor(cz - radius), z);
                    int x1 = Math.max(Mth.floor(cx + radius), x0);
                    int y1 = Math.max(Mth.floor(cy + radius), y0);
                    int z1 = Math.max(Mth.floor(cz + radius), z0);
                    if (y1 - y0 >= ysq.length) {
                        ysq = new double[y1 - y0 + 1];
                        SQ_Y.set(ysq);
                    }
                    if (z1 - z0 >= zsq.length) {
                        zsq = new double[z1 - z0 + 1];
                        SQ_Z.set(zsq);
                    }
                    // the same quotients as vanilla's loops, squared once per sphere instead of per block
                    for (int by = y0; by <= y1; by++) {
                        double d6 = ((double) by + 0.5 - cy) / radius;
                        ysq[by - y0] = d6 * d6;
                    }
                    for (int bz = z0; bz <= z1; bz++) {
                        double d7 = ((double) bz + 0.5 - cz) / radius;
                        zsq[bz - z0] = d7 * d7;
                    }
                    // zsq falls then rises. So for a row, the z values that pass (sum < 1.0, the same rounding as vanilla's test)
                    // form one run around the smallest zsq. The run is found from there and walked in vanilla's ascending order.
                    int nz = z1 - z0 + 1;
                    int zMin = 0;
                    for (int k = 1; k < nz; k++) {
                        if (zsq[k] < zsq[zMin]) {
                            zMin = k;
                        }
                    }
                    long box = byRows ? span(z0 - z, nz) : 0L;
                    for (int bx = x0; bx <= x1; bx++) {
                        double d5 = ((double) bx + 0.5 - cx) / radius;
                        double sq5 = d5 * d5;
                        if (sq5 < 1.0) {
                            // the run's ends start from the previous row's. Along y the run grows then shrinks, so each end moves a step or two.
                            int plo = zMin;
                            int phi = zMin;
                            for (int by = y0; by <= y1; by++) {
                                double sq56 = sq5 + ysq[by - y0];
                                if (sq56 < 1.0 && !(by < buildMin || by >= buildMax)) {
                                    int row = bx - x + (by - y) * width;
                                    if (byRows && (rows[row] & box) == box) {
                                        continue; // every block this sphere's run could reach in the row was visited
                                    }
                                    if (!(sq56 + zsq[zMin] < 1.0)) {
                                        continue;
                                    }
                                    int lo = plo;
                                    while (lo > 0 && sq56 + zsq[lo - 1] < 1.0) {
                                        lo--;
                                    }
                                    while (lo < zMin && !(sq56 + zsq[lo] < 1.0)) {
                                        lo++;
                                    }
                                    int hi = phi;
                                    while (hi < nz - 1 && sq56 + zsq[hi + 1] < 1.0) {
                                        hi++;
                                    }
                                    while (hi > zMin && !(sq56 + zsq[hi] < 1.0)) {
                                        hi--;
                                    }
                                    plo = lo;
                                    phi = hi;
                                    if (byRows) {
                                        long run = span(z0 + lo - z, hi - lo + 1);
                                        long fresh = run & ~rows[row];
                                        rows[row] |= run;
                                        while (fresh != 0) {
                                            int dz = Long.numberOfTrailingZeros(fresh);
                                            fresh &= fresh - 1;
                                            cells.visit(bx, by, z + dz);
                                        }
                                    } else {
                                        for (int bz = z0 + lo; bz <= z0 + hi; bz++) {
                                            int bit = row + (bz - z) * wh;
                                            if ((visited[bit >>> 6] & (1L << bit)) == 0) {
                                                visited[bit >>> 6] |= 1L << bit;
                                                cells.visit(bx, by, bz);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            sections.close();
        }
        placed = cells.placed;
        cells.end();
        FeatureStats.fast(OreFeature.class);
        return placed > 0;
    }

    /** Bits [from, from + len) of a long. */
    private static long span(int from, int len) {
        return (len >= 64 ? -1L : (1L << len) - 1) << from;
    }

    /** Whether every sphere's box stays within width (x) and height (y) of the box start, and within 64 in z. */
    private static boolean fitsRows(int width, int height, int x, int y, int z, double[] spheres, int size) {
        for (int s = 0; s < size; s++) {
            double radius = spheres[s * 4 + 3];
            if (!(radius < 0.0)) {
                int x1 = Math.max(Mth.floor(spheres[s * 4] + radius), Math.max(Mth.floor(spheres[s * 4] - radius), x));
                int y1 = Math.max(Mth.floor(spheres[s * 4 + 1] + radius), Math.max(Mth.floor(spheres[s * 4 + 1] - radius), y));
                int z1 = Math.max(Mth.floor(spheres[s * 4 + 2] + radius), Math.max(Mth.floor(spheres[s * 4 + 2] - radius), z));
                if (x1 - x >= width || y1 - y >= height || z1 - z >= 64) {
                    return false;
                }
            }
        }
        return true;
    }

    private static final ThreadLocal<long[]> ROWS = ThreadLocal.withInitial(() -> new long[512]);

    /** Cleared row masks for {@code n} rows. */
    private static long[] rows(int n) {
        long[] rows = ROWS.get();
        if (rows.length < n) {
            rows = new long[Math.max(n, rows.length * 2)];
            ROWS.set(rows);
        } else {
            java.util.Arrays.fill(rows, 0, n, 0L);
        }
        return rows;
    }

    private static final ThreadLocal<Cells> CELLS = ThreadLocal.withInitial(Cells::new);

    /** {@code OreFeature.doPlace}'s work at a block it visits the first time (one per thread, reused). */
    private static final class Cells {
        /** The thread's section access and position, reused by every placement. */
        final Sections ownSections = new Sections();
        final BlockPos.MutableBlockPos ownMutable = new BlockPos.MutableBlockPos();
        final dev.denismasterherobrine.generatoraccelerator2.features.region.SectionCursor ownCursor =
            new dev.denismasterherobrine.generatoraccelerator2.features.region.SectionCursor();
        Ga2Region region;
        RandomSource random;
        OreConfiguration config;
        OreTargets targets;
        Sections sections;
        BlockPos.MutableBlockPos mutable;
        boolean upgrading;
        int upMin;
        int upMax;
        float discard;
        BlockState lastState;
        int lastMask;
        int placed;

        void start(Ga2Region region, RandomSource random, OreConfiguration config, OreTargets targets, Sections sections, BlockPos.MutableBlockPos mutable) {
            this.region = region;
            this.random = random;
            this.config = config;
            this.targets = targets;
            this.sections = sections;
            this.mutable = mutable;
            this.upgrading = region.ga2$upgrading();
            this.upMin = region.ga2$upgradeMinY();
            this.upMax = region.ga2$upgradeMaxY();
            this.discard = config.discardChanceOnAirExposure;
            this.lastState = null;
            this.lastMask = 0;
            this.placed = 0;
        }

        void end() {
            region = null;
            random = null;
            config = null;
            targets = null;
            sections = null;
            mutable = null;
            lastState = null;
        }

        void visit(int bx, int by, int bz) {
            boolean canWrite;
            if (region.ga2$inWriteRadius(bx >> 4, bz >> 4)) {
                canWrite = !upgrading || (by >= upMin && by < upMax);
            } else {
                canWrite = region.ga2$ensureCanWrite(mutable.set(bx, by, bz)); // logs the far write like vanilla
            }
            if (!canWrite) {
                return;
            }
            LevelChunkSection section = sections.section(bx, by, bz);
            if (section == null) {
                return;
            }
            int lx = bx & 15;
            int ly = by & 15;
            int lz = bz & 15;
            BlockState state = dev.denismasterherobrine.generatoraccelerator2.chunkio.StateReads.get(section, lx, ly, lz);
            if (targets != null) {
                int mask;
                if (state == lastState) {
                    mask = lastMask;
                } else {
                    mask = targets.mask(state);
                    lastState = state;
                    lastMask = mask;
                }
                while (mask != 0) {
                    int t = Integer.numberOfTrailingZeros(mask);
                    mask &= mask - 1;
                    if (shouldSkipAirCheck(random, discard) || !adjacentToAir(sections, bx, by, bz)) {
                        section.setBlockState(lx, ly, lz, targets.target(t).state, false);
                        placed++;
                        break;
                    }
                }
            } else {
                for (OreConfiguration.TargetBlockState target : config.targetStates) {
                    if (target.target.test(state, random) && (shouldSkipAirCheck(random, discard) || !adjacentToAir(sections, bx, by, bz))) {
                        section.setBlockState(lx, ly, lz, target.state, false);
                        placed++;
                        break;
                    }
                }
            }
        }
    }

    /** A cleared bitmap that covers every index the spheres can produce ({@code bx - x + (by - y) * width + (bz - z) * width * height}). */
    private static long[] visited(int width, int height, int x, int y, int z, double[] spheres, int size) {
        long max = 0;
        for (int s = 0; s < size; s++) {
            double radius = spheres[s * 4 + 3];
            if (!(radius < 0.0)) {
                long bx = Math.max(Mth.floor(spheres[s * 4] + radius), Math.max(Mth.floor(spheres[s * 4] - radius), x)) - (long) x;
                long by = Math.max(Mth.floor(spheres[s * 4 + 1] + radius), Math.max(Mth.floor(spheres[s * 4 + 1] - radius), y)) - (long) y;
                long bz = Math.max(Mth.floor(spheres[s * 4 + 2] + radius), Math.max(Mth.floor(spheres[s * 4 + 2] - radius), z)) - (long) z;
                max = Math.max(max, bx + by * width + bz * (long) width * height);
            }
        }
        int words = (int) (max >>> 6) + 1;
        long[] visited = VISITED.get();
        if (visited.length < words) {
            visited = new long[Math.max(words, visited.length * 2)];
            VISITED.set(visited);
        } else {
            java.util.Arrays.fill(visited, 0, words, 0L);
        }
        return visited;
    }

    /** Exact {@code OreFeature.shouldSkipAirCheck}. */
    private static boolean shouldSkipAirCheck(RandomSource random, float chance) {
        if (chance <= 0.0F) {
            return true;
        }
        return chance >= 1.0F ? false : random.nextFloat() >= chance;
    }

    /** Exact {@code Feature.isAdjacentToAir} through {@code BulkSectionAccess}: DOWN, UP, NORTH, SOUTH, WEST, EAST. */
    private static boolean adjacentToAir(Sections sections, int x, int y, int z) {
        return sections.state(x, y - 1, z).isAir() || sections.state(x, y + 1, z).isAir() || sections.state(x, y, z - 1).isAir()
            || sections.state(x, y, z + 1).isAir() || sections.state(x - 1, y, z).isAir() || sections.state(x + 1, y, z).isAir();
    }
}
