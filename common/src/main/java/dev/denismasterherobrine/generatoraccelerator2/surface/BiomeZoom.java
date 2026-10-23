package dev.denismasterherobrine.generatoraccelerator2.surface;

import dev.denismasterherobrine.generatoraccelerator2.mixin.surface.BiomeManagerAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.LinearCongruentialGenerator;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;

/**
 * {@code BiomeManager.getBiome} for the blocks of one chunk with the per-quart-point work cached.
 *
 * <p>For a block, vanilla jitters each of the 8 surrounding quart points by three "fiddle" offsets.
 * The offsets derive from the zoom seed and the point's coordinates. Vanilla picks the closest
 * jittered point and asks the noise biome source for that point. A point's offsets and the source's
 * answer for the point are shared by every block around it, so both are cached per point of the
 * chunk's quart box. When all 8 candidates hold the same biome, the answer is that biome whichever
 * point wins, so no distance is computed. Otherwise the distance arithmetic and the tie order
 * (strict {@code >}, lowest corner index wins) are vanilla's. Instances are reused per thread.
 */
public final class BiomeZoom {
    private static final ThreadLocal<BiomeZoom> CACHE = ThreadLocal.withInitial(BiomeZoom::new);
    /**
     * A second instance per thread for generation regions, which outlive a surface pass on the same thread. Held weakly: the
     * region keeps its zoom, and the zoom keeps the region as its owner, so between steps only this slot kept them both
     * reachable, with the region's whole grid of chunks. Under another chunk system nothing calls {@link #releaseThread}, and a
     * strong slot kept every thread's last region for good. While the thread's current region is alive, the instance is too, so
     * the next region on the thread still takes it over as before.
     */
    private static final ThreadLocal<java.lang.ref.WeakReference<BiomeZoom>> REGION_CACHE = ThreadLocal.withInitial(() -> new java.lang.ref.WeakReference<>(null));

    private static final int NXZ = 6;
    private static final int LAYER = NXZ * NXZ;

    private BiomeManager.NoiseBiomeSource source;
    /** Set when another mod changes {@code BiomeManager.getBiome}. Lookups then go through this manager unchanged. */
    private BiomeManager direct;
    private Object owner;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private long seed;
    private int qx0;
    private int qz0;
    private int qy0;
    private int ny;
    private int stamp;
    // per quart point of the chunk's box: index (iy * NXZ + iz) * NXZ + ix
    private double[] fiddles = new double[0];
    @SuppressWarnings("unchecked")
    private Holder<Biome>[] biomes = new Holder[0];
    private int[] pointStamp = new int[0];
    /** Per point: the stamp its fiddle offsets were computed at (only points next to a biome border need them). */
    private int[] fiddleStamp = new int[0];
    // current column
    private int colX = Integer.MIN_VALUE;
    private int colZ = Integer.MIN_VALUE;
    private int colL;
    private int colJ1;
    private double colD0;
    private double colD2;
    private boolean colOk;
    private int colBase;
    /** Quart layer of the last lookup in this column and, when its 8 candidates agree, their biome. */
    private int lastI1 = Integer.MIN_VALUE;
    private Holder<Biome> lastUniform;

    private BiomeZoom() {}

    /**
     * The zoom for a chunk whose blocks span {@code [minX, minX + 16) x [minY, maxY) x [minZ, minZ + 16)}.
     * Points outside the cached box are computed without caching.
     */
    public static BiomeZoom forChunk(BiomeManager manager, int minX, int minZ, int minY, int maxY) {
        return configure(CACHE.get(), manager, minX, minZ, minY, maxY);
    }

    /**
     * The zoom a generation region answers its biome lookups from: this thread's region instance, configured for the
     * region's centre chunk over the whole build height. The next region on the thread takes the instance over, so a
     * lookup first checks {@link #owner()}.
     */
    public static BiomeZoom forRegion(BiomeManager manager, int minX, int minZ, int minY, int maxY, Object owner) {
        BiomeZoom instance = REGION_CACHE.get().get();
        if (instance == null) {
            instance = new BiomeZoom();
            REGION_CACHE.set(new java.lang.ref.WeakReference<>(instance));
        }
        BiomeZoom zoom = configure(instance, manager, minX, minZ, minY, maxY);
        zoom.owner = owner;
        return zoom;
    }

    /** The region {@link #forRegion} configured this instance for, or null. */
    public Object owner() {
        return owner;
    }

    private static BiomeZoom configure(BiomeZoom zoom, BiomeManager manager, int minX, int minZ, int minY, int maxY) {
        zoom.direct = SurfaceHooks.zoomAllowed() && manager.getClass() == BiomeManager.class ? null : manager;
        BiomeManagerAccessor acc = (BiomeManagerAccessor) manager;
        zoom.source = acc.ga2$noiseBiomeSource();
        zoom.seed = acc.ga2$biomeZoomSeed();
        zoom.qx0 = (minX - 2) >> 2;
        zoom.qz0 = (minZ - 2) >> 2;
        zoom.qy0 = (minY - 2) >> 2;
        zoom.ny = (((maxY + 1 - 2) >> 2) + 1) - zoom.qy0 + 1;
        int points = LAYER * zoom.ny;
        if (zoom.pointStamp.length < points) {
            zoom.fiddles = new double[points * 3];
            @SuppressWarnings("unchecked")
            Holder<Biome>[] b = new Holder[points];
            zoom.biomes = b;
            zoom.pointStamp = new int[points];
            zoom.fiddleStamp = new int[points];
            zoom.stamp = 0;
        }
        if (++zoom.stamp == 0) {
            java.util.Arrays.fill(zoom.pointStamp, 0);
            java.util.Arrays.fill(zoom.fiddleStamp, 0);
            zoom.stamp = 1;
        }
        zoom.colX = Integer.MIN_VALUE;
        zoom.lastI1 = Integer.MIN_VALUE;
        zoom.lastUniform = null;
        zoom.owner = null;
        return zoom;
    }

    /** True when the 8 candidate points of a lookup at the block lie in this zoom's box (answered from the stored biomes). */
    public boolean covers(int x, int y, int z) {
        if (direct != null) {
            return false;
        }
        int ix = ((x - 2) >> 2) - qx0;
        int iz = ((z - 2) >> 2) - qz0;
        int iy = ((y - 2) >> 2) - qy0;
        return ix >= 0 && ix + 1 < NXZ && iz >= 0 && iz + 1 < NXZ && iy >= 0 && iy + 1 < ny;
    }

    /** Drops the references this thread's instance holds to the last chunk. */
    public void release() {
        source = null;
        direct = null;
        owner = null;
        lastUniform = null;
        java.util.Arrays.fill(biomes, null);
    }

    /**
     * Drops what this thread's two instances hold. A region's instance keeps its region as owner and biome source until the next
     * region on the thread takes the instance over ({@code RegionRefs}). The region in turn keeps its task's chunks.
     */
    public static void releaseThread() {
        BiomeZoom region = REGION_CACHE.get().get();
        if (region != null && (region.owner != null || region.source != null)) {
            region.release();
        }
        BiomeZoom chunk = CACHE.get();
        if (chunk.source != null) {
            chunk.release();
        }
    }

    private void column(int x, int z) {
        if (x != colX || z != colZ) {
            int i = x - 2;
            int k = z - 2;
            colX = x;
            colZ = z;
            colL = i >> 2;
            colJ1 = k >> 2;
            colD0 = (double) (i & 3) / 4.0;
            colD2 = (double) (k & 3) / 4.0;
            int ix = colL - qx0;
            int iz = colJ1 - qz0;
            colOk = ix >= 0 && ix + 1 < NXZ && iz >= 0 && iz + 1 < NXZ;
            colBase = iz * NXZ + ix;
            lastI1 = Integer.MIN_VALUE;
        }
    }

    /** {@code BiomeManager.getBiome(new BlockPos(x, y, z))}. */
    public Holder<Biome> biome(int x, int y, int z) {
        if (direct != null) {
            return direct.getBiome(pos.set(x, y, z));
        }
        column(x, z);
        int j = y - 2;
        int i1 = j >> 2;
        if (i1 == lastI1 && lastUniform != null) {
            return lastUniform;
        }
        int iy = i1 - qy0;
        if (!colOk || iy < 0 || iy + 1 >= ny) {
            return biomeUncached(x, y, z);
        }
        // vanilla corner bits: 4 = x + 1, 2 = y + 1, 1 = z + 1. Offsets +1, +LAYER, +NXZ
        int lo = iy * LAYER + colBase;
        int hi = lo + LAYER;
        int l = colL;
        int jz = colJ1;
        // load all 8 points (the distance loop below reads every one of them)
        Holder<Biome> b0 = point(lo, l, i1, jz);
        Holder<Biome> b1 = point(lo + NXZ, l, i1, jz + 1);
        Holder<Biome> b2 = point(lo + 1, l + 1, i1, jz);
        Holder<Biome> b3 = point(lo + 1 + NXZ, l + 1, i1, jz + 1);
        Holder<Biome> b4 = point(hi, l, i1 + 1, jz);
        Holder<Biome> b5 = point(hi + NXZ, l, i1 + 1, jz + 1);
        Holder<Biome> b6 = point(hi + 1, l + 1, i1 + 1, jz);
        Holder<Biome> b7 = point(hi + 1 + NXZ, l + 1, i1 + 1, jz + 1);
        lastI1 = i1;
        if (b0 == b1 && b0 == b2 && b0 == b3 && b0 == b4 && b0 == b5 && b0 == b6 && b0 == b7) {
            lastUniform = b0;
            return b0;
        }
        lastUniform = null;
        double d0 = colD0;
        double d1 = (double) (j & 3) / 4.0;
        double d2 = colD2;
        int k1 = 0;
        double d3 = Double.POSITIVE_INFINITY;
        for (int l1 = 0; l1 < 8; l1++) {
            boolean flag = (l1 & 4) == 0;
            boolean flag1 = (l1 & 2) == 0;
            boolean flag2 = (l1 & 1) == 0;
            double d4 = flag ? d0 : d0 - 1.0;
            double d5 = flag1 ? d1 : d1 - 1.0;
            double d6 = flag2 ? d2 : d2 - 1.0;
            int p = (flag1 ? lo : hi) + (flag ? 0 : 1) + (flag2 ? 0 : NXZ);
            if (fiddleStamp[p] != stamp) {
                fiddlesAt(p, flag ? l : l + 1, flag1 ? i1 : i1 + 1, flag2 ? jz : jz + 1);
            }
            int f = p * 3;
            double d7 = Mth.square(d6 + fiddles[f + 2]) + Mth.square(d5 + fiddles[f + 1]) + Mth.square(d4 + fiddles[f]);
            if (d3 > d7) {
                k1 = l1;
                d3 = d7;
            }
        }
        return biomes[((k1 & 2) == 0 ? lo : hi) + ((k1 & 4) == 0 ? 0 : 1) + ((k1 & 1) == 0 ? 0 : NXZ)];
    }

    /** The noise biome at a cached quart point, loaded on first use. */
    private Holder<Biome> point(int idx, int qx, int qy, int qz) {
        if (pointStamp[idx] != stamp) {
            biomes[idx] = source.getNoiseBiome(qx, qy, qz);
            pointStamp[idx] = stamp;
        }
        return biomes[idx];
    }

    /** A cached quart point's three fiddle offsets, computed on first use. */
    private void fiddlesAt(int idx, int qx, int qy, int qz) {
        long h = LinearCongruentialGenerator.next(seed, (long) qx);
        h = LinearCongruentialGenerator.next(h, (long) qy);
        h = LinearCongruentialGenerator.next(h, (long) qz);
        h = LinearCongruentialGenerator.next(h, (long) qx);
        h = LinearCongruentialGenerator.next(h, (long) qy);
        h = LinearCongruentialGenerator.next(h, (long) qz);
        fiddles[idx * 3] = fiddle(h);
        h = LinearCongruentialGenerator.next(h, seed);
        fiddles[idx * 3 + 1] = fiddle(h);
        h = LinearCongruentialGenerator.next(h, seed);
        fiddles[idx * 3 + 2] = fiddle(h);
        fiddleStamp[idx] = stamp;
    }

    /** Vanilla's computation for blocks whose candidate points fall outside the cached box. */
    private Holder<Biome> biomeUncached(int x, int y, int z) {
        int i = x - 2;
        int j = y - 2;
        int k = z - 2;
        int l = i >> 2;
        int i1 = j >> 2;
        int j1 = k >> 2;
        double d0 = (double) (i & 3) / 4.0;
        double d1 = (double) (j & 3) / 4.0;
        double d2 = (double) (k & 3) / 4.0;
        int k1 = 0;
        double d3 = Double.POSITIVE_INFINITY;
        for (int l1 = 0; l1 < 8; l1++) {
            boolean flag = (l1 & 4) == 0;
            boolean flag1 = (l1 & 2) == 0;
            boolean flag2 = (l1 & 1) == 0;
            int i2 = flag ? l : l + 1;
            int j2 = flag1 ? i1 : i1 + 1;
            int k2 = flag2 ? j1 : j1 + 1;
            double d4 = flag ? d0 : d0 - 1.0;
            double d5 = flag1 ? d1 : d1 - 1.0;
            double d6 = flag2 ? d2 : d2 - 1.0;
            long h = LinearCongruentialGenerator.next(seed, (long) i2);
            h = LinearCongruentialGenerator.next(h, (long) j2);
            h = LinearCongruentialGenerator.next(h, (long) k2);
            h = LinearCongruentialGenerator.next(h, (long) i2);
            h = LinearCongruentialGenerator.next(h, (long) j2);
            h = LinearCongruentialGenerator.next(h, (long) k2);
            double f0 = fiddle(h);
            h = LinearCongruentialGenerator.next(h, seed);
            double f1 = fiddle(h);
            h = LinearCongruentialGenerator.next(h, seed);
            double f2 = fiddle(h);
            double d7 = Mth.square(d6 + f2) + Mth.square(d5 + f1) + Mth.square(d4 + f0);
            if (d3 > d7) {
                k1 = l1;
                d3 = d7;
            }
        }
        int l2 = (k1 & 4) == 0 ? l : l + 1;
        int i3 = (k1 & 2) == 0 ? i1 : i1 + 1;
        int j3 = (k1 & 1) == 0 ? j1 : j1 + 1;
        return source.getNoiseBiome(l2, i3, j3);
    }

    private static double fiddle(long h) {
        double d0 = (double) Math.floorMod(h >> 24, 1024) / 1024.0;
        return (d0 - 0.5) * 0.9;
    }
}
