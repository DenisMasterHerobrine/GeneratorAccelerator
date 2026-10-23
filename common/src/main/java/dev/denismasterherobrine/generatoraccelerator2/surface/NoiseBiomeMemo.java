package dev.denismasterherobrine.generatoraccelerator2.surface;

import dev.denismasterherobrine.generatoraccelerator2.mixin.surface.BiomeManagerAccessor;
import java.util.Arrays;
import net.minecraft.core.Holder;
import net.minecraft.util.LinearCongruentialGenerator;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * {@code BiomeManager.getBiome} for other mods' code that asks a biome source through a manager of its own
 * ({@code withDifferentSource}) for many blocks in one place. While a scope is open on the thread, the source's answer per
 * quart point is remembered. Vanilla picks the closest of the 8 quart points around a block (after their fiddle offsets)
 * and asks the source for that one point. Blocks near each other mostly pick the same points. So a chunk's worth of
 * lookups asks the source for a fifth of the points or fewer. OTBWG's crag gardens, for example, make one lookup per
 * column. The arithmetic and tie order are vanilla's ({@link BiomeZoom}'s uncached path). Only a point's answer is reused.
 *
 * <p>A scope is opened only around code whose manager's source is known to answer from the position alone. Compat code
 * that reviewed the source opens the scope, for example for a biome source with the level's climate sampler. A source
 * that is a level or a chunk is never remembered, because its answers change as chunks generate. Neither is a second
 * source in the same scope. Another mod's change to the zoom keeps vanilla's lookup.
 */
public final class NoiseBiomeMemo implements AutoCloseable {
    private static final ThreadLocal<NoiseBiomeMemo> INSTANCE = ThreadLocal.withInitial(NoiseBiomeMemo::new);
    private static final ThreadLocal<NoiseBiomeMemo> OPEN = new ThreadLocal<>();

    private BiomeManager.NoiseBiomeSource source;
    private int mask = 255;
    private int size;
    private int[] qx = new int[256];
    private int[] qy = new int[256];
    private int[] qz = new int[256];
    @SuppressWarnings("unchecked")
    private Holder<Biome>[] biomes = new Holder[256];
    private long lookups;
    private long asked;

    private NoiseBiomeMemo() {}

    /** Opens this thread's scope (closed with {@link #close}). Scopes do not nest. */
    public static NoiseBiomeMemo open() {
        NoiseBiomeMemo memo = INSTANCE.get();
        memo.source = null;
        memo.size = 0;
        OPEN.set(memo);
        return memo;
    }

    /** The scope open on this thread, or null. */
    public static NoiseBiomeMemo current() {
        return OPEN.get();
    }

    @Override
    public void close() {
        OPEN.set(null);
        source = null;
        Arrays.fill(biomes, 0, biomes.length, null);
        size = 0;
        NoiseBiomeStats.LOOKUPS.add(lookups);
        NoiseBiomeStats.ASKED.add(asked);
        lookups = 0;
        asked = 0;
    }

    /**
     * {@code manager.getBiome(new BlockPos(x, y, z))}, or null when this scope does not answer for the manager (the caller
     * then runs vanilla's lookup).
     */
    public Holder<Biome> biome(BiomeManager manager, int x, int y, int z) {
        if (manager.getClass() != BiomeManager.class || !SurfaceHooks.zoomAllowed()) {
            return null;
        }
        BiomeManagerAccessor acc = (BiomeManagerAccessor) manager;
        BiomeManager.NoiseBiomeSource src = acc.ga2$noiseBiomeSource();
        if (src != source) {
            if (source != null || src instanceof LevelReader || src instanceof ChunkAccess) {
                return null;
            }
            source = src;
        }
        long seed = acc.ga2$biomeZoomSeed();
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
        lookups++;
        return point((k1 & 4) == 0 ? l : l + 1, (k1 & 2) == 0 ? i1 : i1 + 1, (k1 & 1) == 0 ? j1 : j1 + 1);
    }

    /** The source's answer for a quart point, asked once per scope. */
    private Holder<Biome> point(int x, int y, int z) {
        int slot = hash(x, y, z) & mask;
        while (true) {
            Holder<Biome> b = biomes[slot];
            if (b == null) {
                break;
            }
            if (qx[slot] == x && qy[slot] == y && qz[slot] == z) {
                return b;
            }
            slot = (slot + 1) & mask;
        }
        Holder<Biome> biome = source.getNoiseBiome(x, y, z);
        asked++;
        if (biome == null) {
            // not a biome: vanilla's lookup hands it on as it is
            return null;
        }
        if ((size + 1) * 2 > mask + 1) {
            grow();
            slot = hash(x, y, z) & mask;
            while (biomes[slot] != null) {
                slot = (slot + 1) & mask;
            }
        }
        qx[slot] = x;
        qy[slot] = y;
        qz[slot] = z;
        biomes[slot] = biome;
        size++;
        return biome;
    }

    private void grow() {
        int[] ox = qx;
        int[] oy = qy;
        int[] oz = qz;
        Holder<Biome>[] ob = biomes;
        int capacity = (mask + 1) * 2;
        mask = capacity - 1;
        qx = new int[capacity];
        qy = new int[capacity];
        qz = new int[capacity];
        @SuppressWarnings("unchecked")
        Holder<Biome>[] nb = new Holder[capacity];
        biomes = nb;
        for (int s = 0; s < ob.length; s++) {
            if (ob[s] != null) {
                int slot = hash(ox[s], oy[s], oz[s]) & mask;
                while (biomes[slot] != null) {
                    slot = (slot + 1) & mask;
                }
                qx[slot] = ox[s];
                qy[slot] = oy[s];
                qz[slot] = oz[s];
                biomes[slot] = ob[s];
            }
        }
    }

    private static int hash(int x, int y, int z) {
        int h = x * 0x9E3779B1 ^ z * 0x85EBCA77 ^ y * 0xC2B2AE3D;
        return h ^ (h >>> 15);
    }

    private static double fiddle(long h) {
        double d0 = (double) Math.floorMod(h >> 24, 1024) / 1024.0;
        return (d0 - 0.5) * 0.9;
    }

    /** Lookups answered in scopes and points the sources were asked for ({@code /ga2 steps stats}). */
    public static final class NoiseBiomeStats {
        private NoiseBiomeStats() {}

        public static final java.util.concurrent.atomic.LongAdder LOOKUPS = new java.util.concurrent.atomic.LongAdder();
        public static final java.util.concurrent.atomic.LongAdder ASKED = new java.util.concurrent.atomic.LongAdder();
    }
}
