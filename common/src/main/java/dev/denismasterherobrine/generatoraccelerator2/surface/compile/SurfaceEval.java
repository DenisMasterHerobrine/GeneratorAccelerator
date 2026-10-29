package dev.denismasterherobrine.generatoraccelerator2.surface.compile;

import dev.denismasterherobrine.generatoraccelerator2.chunkio.ChunkBlocks;
import dev.denismasterherobrine.generatoraccelerator2.mixin.surface.SurfaceSystemAccessor;
import dev.denismasterherobrine.generatoraccelerator2.surface.BiomeZoom;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Evaluation state of one surface build (one chunk): what vanilla's {@code SurfaceRules.Context}
 * holds, with the seed-dependent tables of a {@link SurfaceProgram} bound to the chunk's random
 * state and generation context. Every condition method transcribes the vanilla condition's
 * {@code compute()}. Values that vanilla caches per column are cached per column here too.
 */
public final class SurfaceEval {
    public final SurfaceProgram program;
    private final SurfaceSystemAccessor system;
    private final NormalNoise[] noises;
    private final PositionalDraws[] gradFactories;
    public final int[] gradLo;
    public final int[] gradHi;
    public final int[] anchorY;
    private final Predicate<Holder<Biome>>[] biomeTests;
    private final Heightmap worldSurface;
    private final BiomeZoom zoom;
    private final NormalNoise surfaceNoise;
    private final PositionalDraws noiseRandom;
    /** Noise slot sharing the instance of the surface depth noise, or -1. */
    private final int surfaceNoiseSlot;
    final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    /** Per-chunk vanilla objects built from the program's opaque sources, by opaque index. */
    public SurfaceRules.SurfaceRule[] opaqueRules;
    public SurfaceRules.Condition[] opaqueConds;

    // column
    public int blockX;
    public int blockZ;
    public int surfaceDepth;
    public int minSurface;
    private boolean secondaryDone;
    private double secondary;
    private final double[] noiseValue;
    private final boolean[] noiseDone;
    private int steep;

    // block
    public int blockY;
    public int stoneDepthAbove;
    public int waterHeight;
    private Holder<Biome> biome;

    /*
     * stoneDepthBelow = blockY - k2 + 1, k2 being the bottom of the current run of solid blocks. Vanilla
     * finds k2 by scanning down at the top block of each run. This class finds k2 on first use instead,
     * scanning down from the current block. Blocks below the current block are untouched so far, and the
     * run above it was solid when vanilla would have scanned, so both scans stop at the same block.
     */
    private ChunkBlocks columnBlocks;
    private int columnLx;
    private int columnLz;
    private int columnMinY;
    private int runBottom = Integer.MAX_VALUE;
    private boolean eagerBelow;
    private int eagerBelowValue;

    // biome condition results per biome holder for this chunk. A holder's key never changes, and a biome set's tags are
    // read once per chunk
    private final IdentityHashMap<Holder<Biome>, byte[]> biomeMatches = new IdentityHashMap<>();
    private Holder<Biome> matchHolder;
    private byte[] matches;

    // interpreter caches: the value as 0 or 1, valid while its stamp equals the current block or column stamp
    final byte[] cache;
    final int[] cacheStamp;
    int blockStamp;
    int columnStamp;

    @SuppressWarnings("unchecked")
    public SurfaceEval(SurfaceProgram program, SurfaceSystem system, RandomState randomState, WorldGenerationContext genContext, Heightmap worldSurface,
                       BiomeZoom zoom) {
        this.program = program;
        this.system = (SurfaceSystemAccessor) system;
        this.randomState = randomState;
        this.noises = new NormalNoise[program.noiseKeys.size()];
        this.surfaceNoise = this.system.ga2$surfaceNoise();
        this.noiseRandom = new PositionalDraws(this.system.ga2$noiseRandom());
        int surfaceSlot = -1;
        for (int i = 0; i < noises.length; i++) {
            noises[i] = randomState.getOrCreateNoise(program.noiseKeys.get(i));
            if (noises[i] == surfaceNoise) {
                surfaceSlot = i;
            }
        }
        this.surfaceNoiseSlot = surfaceSlot;
        int g = program.gradients.size();
        this.gradFactories = new PositionalDraws[g];
        this.gradLo = new int[g];
        this.gradHi = new int[g];
        for (int i = 0; i < g; i++) {
            SurfaceRules.VerticalGradientConditionSource src = program.gradients.get(i);
            gradLo[i] = src.trueAtAndBelow().resolveY(genContext);
            gradHi[i] = src.falseAtAndAbove().resolveY(genContext);
            gradFactories[i] = new PositionalDraws(randomState.getOrCreateRandomFactory(src.randomName()));
        }
        this.anchorY = new int[program.anchors.size()];
        for (int i = 0; i < anchorY.length; i++) {
            anchorY[i] = program.anchors.get(i).resolveY(genContext);
        }
        this.biomeTests = program.biomeTests.toArray(new Predicate[0]);
        this.worldSurface = worldSurface;
        this.zoom = zoom;
        this.noiseValue = new double[noises.length];
        this.noiseDone = new boolean[noises.length];
        this.cache = new byte[program.condCount];
        this.cacheStamp = new int[program.condCount];
        this.blockStamp = 1;
        this.columnStamp = 1;
    }

    /** {@code Context.updateXZ}: also computes the surface depth exactly like {@code SurfaceSystem.getSurfaceDepth}. */
    public void updateXZ(int x, int z) {
        blockX = x;
        blockZ = z;
        Arrays.fill(noiseDone, false);
        secondaryDone = false;
        steep = 0;
        double noise = surfaceNoise.getValue(x, 0.0, z);
        if (surfaceNoiseSlot >= 0) {
            noiseValue[surfaceNoiseSlot] = noise;
            noiseDone[surfaceNoiseSlot] = true;
        }
        surfaceDepth = (int) (noise * 2.75 + 3.0 + noiseRandom.nextDouble(x, 0, z) * 0.25);
        columnStamp += 2;
        blockStamp += 2;
    }

    /** The column whose blocks the lazy stone-run scan reads. Resets the run. */
    public void column(ChunkBlocks blocks, int lx, int lz, int minY) {
        columnBlocks = blocks;
        columnLx = lx;
        columnLz = lz;
        columnMinY = minY;
        runBottom = Integer.MAX_VALUE;
    }

    /** {@code Context.updateY} with the stone depth below computed on first use. */
    public void updateY(int stoneAbove, int water, int y) {
        stoneDepthAbove = stoneAbove;
        waterHeight = water;
        blockY = y;
        eagerBelow = false;
        biome = null;
        blockStamp += 2;
    }

    /** {@code Context.updateY} with a stone depth below the caller computed as vanilla does. */
    public void updateY(int stoneAbove, int stoneBelow, int water, int y) {
        stoneDepthAbove = stoneAbove;
        eagerBelow = true;
        eagerBelowValue = stoneBelow;
        waterHeight = water;
        blockY = y;
        biome = null;
        blockStamp += 2;
    }

    /** Moves to another block of the column where only {@code y} matters (deep bands). */
    public void moveY(int y) {
        blockY = y;
        biome = null;
        blockStamp += 2;
    }

    public Holder<Biome> biome() {
        Holder<Biome> b = biome;
        if (b == null) {
            b = zoom.biome(blockX, blockY, blockZ);
            biome = b;
        }
        return b;
    }

    // ---- conditions (vanilla compute() bodies) ----

    public boolean biome(int slot) {
        Holder<Biome> b = biome();
        if (b != matchHolder) {
            byte[] m = biomeMatches.get(b);
            if (m == null) {
                m = new byte[biomeTests.length];
                biomeMatches.put(b, m);
            }
            matches = m;
            matchHolder = b;
        }
        byte m = matches[slot];
        if (m == 0) {
            m = biomeTests[slot].test(b) ? (byte) 2 : (byte) 1;
            matches[slot] = m;
        }
        return m == 2;
    }

    public double noiseValue(int slot) {
        if (!noiseDone[slot]) {
            noiseValue[slot] = noises[slot].getValue(blockX, 0.0, blockZ);
            noiseDone[slot] = true;
        }
        return noiseValue[slot];
    }

    public boolean noise(int slot, double min, double max) {
        double d = noiseValue(slot);
        return d >= min && d <= max;
    }

    public boolean gradient(int slot) {
        int y = blockY;
        int lo = gradLo[slot];
        int hi = gradHi[slot];
        if (y <= lo) {
            return true;
        } else if (y >= hi) {
            return false;
        } else {
            double d = Mth.map((double) y, (double) lo, (double) hi, 1.0, 0.0);
            return (double) gradFactories[slot].nextFloat(blockX, y, blockZ) < d;
        }
    }

    public boolean yCheck(int anchor, int mult, boolean addStone) {
        return blockY + (addStone ? stoneDepthAbove : 0) >= anchorY[anchor] + surfaceDepth * mult;
    }

    public boolean water(int offset, int mult, boolean addStone) {
        return waterHeight == Integer.MIN_VALUE || blockY + (addStone ? stoneDepthAbove : 0) >= waterHeight + offset + surfaceDepth * mult;
    }

    public double secondary() {
        if (!secondaryDone) {
            secondary = system.ga2$getSurfaceSecondary(blockX, blockZ);
            secondaryDone = true;
        }
        return secondary;
    }

    public int stoneDepthBelow() {
        if (eagerBelow) {
            return eagerBelowValue;
        }
        if (runBottom >= blockY) {
            int k2 = DimensionType.WAY_BELOW_MIN_Y;
            for (int j3 = blockY - 1; j3 >= columnMinY - 1; j3--) {
                BlockState below = columnBlocks.get(columnLx, j3, columnLz);
                if (below.isAir() || !below.getFluidState().isEmpty()) {
                    k2 = j3 + 1;
                    break;
                }
            }
            runBottom = k2;
        }
        return blockY - runBottom + 1;
    }

    public boolean stoneDepth(int offset, boolean addSurface, int secondaryRange, boolean ceiling) {
        int i = ceiling ? stoneDepthBelow() : stoneDepthAbove;
        int j = addSurface ? surfaceDepth : 0;
        int k = secondaryRange == 0 ? 0 : (int) Mth.map(secondary(), -1.0, 1.0, 0.0, (double) secondaryRange);
        return i <= 1 + offset + j + k;
    }

    /** {@code SteepMaterialCondition.compute()}, evaluated at its first test in the column. */
    public boolean steep() {
        if (steep == 0) {
            int i = blockX & 15;
            int j = blockZ & 15;
            int k = Math.max(j - 1, 0);
            int l = Math.min(j + 1, 15);
            int i1 = worldSurface.getFirstAvailable(i, k) - 1;
            int j1 = worldSurface.getFirstAvailable(i, l) - 1;
            boolean result;
            if (j1 >= i1 + 4) {
                result = true;
            } else {
                int k1 = Math.max(i - 1, 0);
                int l1 = Math.min(i + 1, 15);
                int i2 = worldSurface.getFirstAvailable(k1, j) - 1;
                int j2 = worldSurface.getFirstAvailable(l1, j) - 1;
                result = i2 >= j2 + 4;
            }
            steep = result ? 2 : 1;
        }
        return steep == 2;
    }

    public boolean hole() {
        return surfaceDepth <= 0;
    }

    public boolean aboveSurface() {
        return blockY >= minSurface;
    }

    public boolean temperature() {
        return biome().value().coldEnoughToSnow(pos.set(blockX, blockY, blockZ), ((SurfaceSystem) (Object) system).getSeaLevel());
    }

    public BlockState bands() {
        return system.ga2$getBand(blockX, blockY, blockZ);
    }

    // ---- per-block cached forms of the expensive pure conditions (used by compiled programs) ----

    public boolean cachedBiome(int id, int slot) {
        return biome(slot);
    }

    public boolean cachedTemperature(int id) {
        if (cacheStamp[id] == blockStamp) {
            return cache[id] != 0;
        }
        boolean v = temperature();
        cacheStamp[id] = blockStamp;
        cache[id] = (byte) (v ? 1 : 0);
        return v;
    }

    /**
     * Lithostitched's slope condition ({@code SlopeCondition.Condition.compute}): the spread of the four neighbouring
     * columns' {@code WORLD_SURFACE_WG} heights (clamped to the chunk) within {@code [min, max]}. Like vanilla's
     * steepness it reads the live heightmap, so each use is computed at its first test in the column and kept for it.
     */
    public boolean cachedSlope(int id, int min, int max) {
        if (cacheStamp[id] == columnStamp) {
            return cache[id] != 0;
        }
        boolean v = slope(min, max);
        cacheStamp[id] = columnStamp;
        cache[id] = (byte) (v ? 1 : 0);
        return v;
    }

    public boolean slope(int min, int max) {
        int x = blockX & 15;
        int z = blockZ & 15;
        int north = worldSurface.getFirstAvailable(x, Math.max(z - 1, 0)) - 1;
        int south = worldSurface.getFirstAvailable(x, Math.min(z + 1, 15)) - 1;
        int west = worldSurface.getFirstAvailable(Math.max(x - 1, 0), z) - 1;
        int east = worldSurface.getFirstAvailable(Math.min(x + 1, 15), z) - 1;
        int spread = Math.max(Math.max(north, south), Math.max(east, west)) - Math.min(Math.min(north, south), Math.min(east, west));
        return spread >= min && spread <= max;
    }

    public boolean cachedGradient(int id, int slot) {
        if (cacheStamp[id] == blockStamp) {
            return cache[id] != 0;
        }
        boolean v = gradient(slot);
        cacheStamp[id] = blockStamp;
        cache[id] = (byte) (v ? 1 : 0);
        return v;
    }

    /** {@link Ir.ExternCond}: cached for its scope like the vanilla conditions. */
    public boolean cachedExtern(int id, int index) {
        SurfaceExtern.Cond c = program.externConds.get(index);
        int stamp = c.scope() == Ir.Scope.COLUMN ? columnStamp : blockStamp;
        if (cacheStamp[id] == stamp) {
            return cache[id] != 0;
        }
        boolean v = c.test(this);
        cacheStamp[id] = stamp;
        cache[id] = (byte) (v ? 1 : 0);
        return v;
    }

    public boolean externCond(int index) {
        return program.externConds.get(index).test(this);
    }

    public BlockState externRule(int index) {
        return program.externRules.get(index).apply(this);
    }

    /** The surface system the chunk is built with (for compat code: its noises, random factory and mods' state on it). */
    public SurfaceSystem system() {
        return (SurfaceSystem) (Object) system;
    }

    private final RandomState randomState;

    /** The world's random state (for compat code: the noises a rule names). */
    public RandomState randomState() {
        return randomState;
    }

    private double[] externColumn = new double[0];
    private int[] externColumnStamp = new int[0];

    /**
     * A value compat code keeps for the current column (one slot per use, chosen by the code). {@code NaN} until set in
     * this column.
     */
    public double columnValue(int slot) {
        return slot < externColumn.length && externColumnStamp[slot] == columnStamp ? externColumn[slot] : Double.NaN;
    }

    public void setColumnValue(int slot, double value) {
        if (slot >= externColumn.length) {
            externColumn = Arrays.copyOf(externColumn, slot + 4);
            externColumnStamp = Arrays.copyOf(externColumnStamp, slot + 4);
        }
        externColumn[slot] = value;
        externColumnStamp[slot] = columnStamp;
    }

    public boolean opaqueCond(int index) {
        return opaqueConds[index].test();
    }

    public BlockState opaqueRule(int index) {
        return opaqueRules[index].tryApply(blockX, blockY, blockZ);
    }
}
