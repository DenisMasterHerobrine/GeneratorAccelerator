package dev.denismasterherobrine.generatoraccelerator2.gpu;

import dev.denismasterherobrine.generatoraccelerator2.aquifer.LiquidRegions;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.MixinTargets;
import dev.denismasterherobrine.generatoraccelerator2.mixin.aquifer.FluidStatusAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.AquiferStatusInvoker;
import dev.denismasterherobrine.generatoraccelerator2.surface.ColumnHooks;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomState;
import javax.annotation.Nullable;

/**
 * What the SURFACE and CARVERS steps of a GPU-filled chunk read instead of a NoiseChunk. The data comes from the GPU's work for
 * the chunk. It holds the preliminary surface levels of the chunk's 5 x 5 quart columns, computed as the chunk's NoiseChunk
 * computes them (with the chunk's own flat-cache view). It also holds every aquifer cell of the chunk's grid (centre and
 * {@code FluidStatus}), as the NoiseChunk's aquifer computes them.
 *
 * <p>The chunk then has no NoiseChunk. {@code SurfaceRules.Context} reads the levels from here when it has no NoiseChunk
 * ({@code GpuSurfaceContextMixin}). The surface step does not create one ({@code GpuSurfaceMixin}). GA2's carver runner carves
 * with {@link GpuAquifer}. Any other code that asks the chunk for its NoiseChunk gets one created as vanilla creates it.
 */
public final class GpuTerrain {
    /** Levels requested outside the chunk's quart columns. These are computed on the spot. */
    public static final LongAdder LEVEL_FALLBACKS = new LongAdder();
    public static final LongAdder CHUNKS = new LongAdder();
    private static final HookTargets.Hook HOOK = HookTargets.hook(HookTargets.GPU_TERRAIN);
    private static final HookTargets.Hook SURFACE_BUILD = HookTargets.hook(HookTargets.SURFACE_BUILD);
    private static final HookTargets.Hook TOP_MATERIAL = HookTargets.hook(HookTargets.SURFACE_TOP_MATERIAL);
    /** {@code -Dgeneratoraccelerator2.gpu.terrain=false} gives chunks the GPU filled their NoiseChunk as before (for comparisons). */
    private static final boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.gpu.terrain"));

    /** Per dimension: what turns a chunk's GPU data into answers. */
    public static final class Spec {
        final int cellsPerChunk, minGridY, minY, height, cellHeight;
        final BlockState fluid;
        final Aquifer.FluidPicker picker;
        /**
         * The router's barrier noise. At a point it evaluates as the chunk-wrapped version does. It holds no beardifier, and any flat
         * cache in it holds a function of x and z alone ({@link #unsupported}).
         */
        final DensityFunction barrier;
        /** The surface level function, evaluated as the NoiseChunk does outside its flat-cache range (levels asked beyond the chunk). */
        final DensityFunction levels;
        /**
         * Whether the level function holds cache_2d markers. A NoiseChunk's cache_2d keeps the value of the last column it was asked at.
         * So through a walk (one column, top down), each marker holds its value at the walk's first height ({@code ClEmitter.C2d#WALK}).
         * A walk beyond the chunk reproduces this with markers of its own ({@link #walkLevels}).
         */
        final boolean walk;

        Spec(int cellsPerChunk, int minGridY, int minY, int height, int cellHeight, BlockState fluid, Aquifer.FluidPicker picker, DensityFunction barrier,
             DensityFunction levels) {
            this.cellsPerChunk = cellsPerChunk;
            this.minGridY = minGridY;
            this.minY = minY;
            this.height = height;
            this.cellHeight = cellHeight;
            this.fluid = fluid;
            this.picker = picker;
            this.barrier = barrier;
            this.levels = levels;
            boolean[] cache2d = new boolean[1];
            levels.mapAll(g -> {
                if (g instanceof DensityFunctions.Marker m && m.type() == DensityFunctions.Marker.Type.Cache2D) {
                    cache2d[0] = true;
                }
                return g;
            });
            this.walk = cache2d[0];
        }

        /** Per height from {@code minY - 1}: whether the global fluid picker's status gives lava there. Created on first use. */
        private volatile boolean[] lavaAt;

        /**
         * {@code picker.computeFluid(x, y, z).at(y).is(LAVA)}. Wherever the GPU runs, the picker reads y alone. It is vanilla's picker,
         * or a {@code PickerShape} turned into intervals. So the answers within the build height are cached by y.
         */
        boolean globalLava(int x, int y, int z) {
            boolean[] t = lavaAt;
            if (t == null) {
                t = new boolean[height + 2];
                for (int k = 0; k < t.length; k++) {
                    int yy = minY - 1 + k;
                    t[k] = picker.computeFluid(0, yy, 0).at(yy).is(Blocks.LAVA);
                }
                lavaAt = t;
            }
            int k = y - minY + 1;
            return k >= 0 && k < t.length ? t[k] : picker.computeFluid(x, y, z).at(y).is(Blocks.LAVA);
        }

        /** The level function for one walk. Each cache_2d keeps its first value at a column, as the NoiseChunk's cache_2d does. */
        DensityFunction walkLevels() {
            return levels.mapAll(g -> g instanceof DensityFunctions.Marker m && m.type() == DensityFunctions.Marker.Type.Cache2D ? new LastColumn(m.wrapped()) : g);
        }

        /**
         * The spec of a dimension, or null when the dimension's chunks keep their NoiseChunk. That happens when the barrier noise's
         * chunk-wrapped value at a point differs from its own value (a flat cache or the beardifier in it). It also happens when the
         * surface level function reads the beardifier.
         */
        public static Spec of(RandomState randomState, int cellsPerChunk, int minGridY, int minY, int height, int cellHeight, BlockState fluid,
                              Aquifer.FluidPicker picker, boolean barrierFlatsXz) {
            if (unsupported(randomState, barrierFlatsXz) != null) {
                return null;
            }
            return new Spec(cellsPerChunk, minGridY, minY, height, cellHeight, fluid, picker, randomState.router().barrierNoise(),
                randomState.router().initialDensityWithoutJaggedness());
        }

        /**
         * Why {@link #of} makes no spec for this router, or null when it does. {@code barrierFlatsXz} means every flat cache in the barrier
         * noise holds a function of x and z alone. Such a flat cache then answers at any point as the function itself does.
         */
        public static String unsupported(RandomState randomState, boolean barrierFlatsXz) {
            if (!pointTransparent(randomState.router().barrierNoise(), !barrierFlatsXz)) {
                return barrierFlatsXz ? "the barrier noise holds the beardifier" : "the barrier noise holds a flat cache of a function that reads y, or the beardifier";
            }
            if (!pointTransparent(randomState.router().initialDensityWithoutJaggedness(), false)) {
                return "the surface level function reads the beardifier";
            }
            return null;
        }

        /**
         * Whether evaluating {@code f} itself at a point gives what the NoiseChunk's wrapped function gives there, with an empty
         * blender. The markers of {@code f} are transparent at a single point, with two exceptions. The first is a flat cache inside
         * the chunk's range. {@code flats} false means {@code f} is only asked outside that range. The second is the beardifier
         * marker, which is the chunk's beardifier in a NoiseChunk.
         */
        private static boolean pointTransparent(DensityFunction f, boolean flats) {
            boolean[] bad = new boolean[1];
            f.mapAll(g -> {
                if (g instanceof DensityFunctions.Marker m && m.type() == DensityFunctions.Marker.Type.FlatCache && flats) {
                    bad[0] = true;
                }
                if (g instanceof DensityFunctions.BeardifierOrMarker) {
                    bad[0] = true;
                }
                return g;
            });
            return !bad[0];
        }
    }

    /** {@code NoiseChunk.Cache2D}: the value at the last (x, z) asked, whatever the y. */
    private static final class LastColumn implements DensityFunction {
        private final DensityFunction wrapped;
        private boolean has;
        private int x, z;
        private double value;

        LastColumn(DensityFunction wrapped) {
            this.wrapped = wrapped;
        }

        @Override
        public double compute(FunctionContext ctx) {
            if (!has || ctx.blockX() != x || ctx.blockZ() != z) {
                has = true;
                x = ctx.blockX();
                z = ctx.blockZ();
                value = wrapped.compute(ctx);
            }
            return value;
        }

        @Override
        public void fillArray(double[] values, ContextProvider provider) {
            provider.fillAllDirectly(values, this);
        }

        @Override
        public DensityFunction mapAll(Visitor visitor) {
            return visitor.apply(new LastColumn(wrapped.mapAll(visitor)));
        }

        @Override
        public double minValue() {
            return wrapped.minValue();
        }

        @Override
        public double maxValue() {
            return wrapped.maxValue();
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return wrapped.codec();
        }
    }

    final Spec spec;
    final int[] data;
    final int cx, cz;
    private GpuAquifer aquifer;
    /** Better Caves' liquid regions of the chunk, applied to the carvers' aquifer answers ({@code aquifer.LiquidRegions}), or null. */
    @Nullable
    public LiquidRegions.Table liquids;

    public GpuTerrain(Spec spec, int[] data, int cx, int cz) {
        this.spec = spec;
        this.data = data;
        this.cx = cx;
        this.cz = cz;
    }

    private static volatile Boolean enabled;

    /**
     * Whether a dimension whose surface the CPU builds still takes the GPU's terrain ({@code GpuSteps}). Off: there the step without
     * a NoiseChunk cost more than it saved. {@code -Dgeneratoraccelerator2.gpu.terrainWithoutSurface=true} turns it on.
     */
    public static final boolean WITHOUT_SURFACE = Boolean.getBoolean("generatoraccelerator2.gpu.terrainWithoutSurface");

    /**
     * Whether chunks the GPU fills may go without a NoiseChunk. This requires that no other mod changes the code that reads this data
     * in the NoiseChunk's place. That code is the surface step's call, the rule context's levels, the surface system's methods that hand
     * the NoiseChunk on, the aquifer and the surface level methods. Changes that GA2 runs itself are excluded too, since that code could
     * read the NoiseChunk. The exception is code in the surface system's column loop that GA2 knows leaves the NoiseChunk alone
     * ({@code ColumnHooks.Hook.readsNoiseChunk}). GA2's surface builder hands such code no NoiseChunk.
     */
    public static boolean enabled() {
        Boolean e = enabled;
        if (e == null) {
            enabled = e = ENABLED && HOOK.allowed() && SURFACE_BUILD.unchanged()
                && ColumnHooks.noiseChunkFree()
                && TOP_MATERIAL.unchanged() && HookTargets.handled(HookTargets.SURFACE_TOP_MATERIAL).isEmpty() && MixinTargets.aquiferVanilla()
                && MixinTargets.surfaceLevelAllowed();
        }
        return e;
    }

    /** The first condition of {@link #enabled} that fails, for the GPU's status line. Null when it holds. */
    @Nullable
    public static String disabledReason() {
        if (!ENABLED) {
            return "off (-Dgeneratoraccelerator2.gpu.terrain=false)";
        } else if (!HOOK.allowed()) {
            return "gpu.terrain off";
        } else if (!SURFACE_BUILD.unchanged()) {
            return "another mod changes the surface build " + HookTargets.foreign(HookTargets.SURFACE_BUILD);
        } else if (!ColumnHooks.noiseChunkFree()) {
            return "other mods' code in the surface's column loop may read the NoiseChunk " + HookTargets.handledMixins(HookTargets.SURFACE_BUILD);
        } else if (!TOP_MATERIAL.unchanged() || !HookTargets.handled(HookTargets.SURFACE_TOP_MATERIAL).isEmpty()) {
            return "another mod changes the top material";
        } else if (!MixinTargets.aquiferVanilla()) {
            return "other mods change the aquifer " + MixinTargets.aquiferChanges();
        } else if (!MixinTargets.surfaceLevelAllowed()) {
            return "another mod changes the surface level methods";
        }
        return null;
    }

    public static GpuTerrain of(ChunkAccess chunk) {
        Ga2GpuChunk c = Ga2GpuChunk.of(chunk);
        return c == null ? null : c.ga2$gpuTerrain();
    }

    /**
     * Whether the chunk reads its terrain from the GPU's data. The chunk may also have a NoiseChunk, made for a biome tie. Both give
     * the same answers, and the GPU's answers are already computed.
     */
    public static boolean viewOf(ChunkAccess chunk) {
        return of(chunk) != null;
    }

    /**
     * The differences from a NoiseChunk that ran the CPU's fill (verification), or null when they match. Compares the quart columns'
     * surface levels, every aquifer cell's centre and status, and the aquifer's flag after the fill.
     */
    public String compare(NoiseChunk cpu, PositionalRandomFactory random) {
        Aquifer aquifer = cpu.aquifer();
        boolean flag = aquifer.shouldScheduleFluidUpdate();
        if (flag != fillFlag()) {
            return "fluid update flag after the fill: CPU " + flag + ", GPU " + fillFlag();
        }
        for (int qx = 0; qx < 5; qx++) {
            for (int qz = 0; qz < 5; qz++) {
                int x = (cx * 4 + qx) << 2, z = (cz * 4 + qz) << 2;
                int a = cpu.preliminarySurfaceLevel(x, z), b = data[2 * spec.cellsPerChunk + qx * 5 + qz];
                if (a != b) {
                    return "surface level at " + x + ", " + z + ": CPU " + a + ", GPU " + b;
                }
            }
        }
        if (!(aquifer instanceof AquiferStatusInvoker statuses)) {
            return null;
        }
        for (int cell = 0; cell < spec.cellsPerChunk; cell++) {
            int ii = cell % 3, t = cell / 3, kk = t % 3, jj = t / 3;
            int gx = cx - 1 + ii, gy = spec.minGridY + jj, gz = cz - 1 + kk;
            RandomSource r = random.at(gx, gy, gz);
            int x = gx * 16 + r.nextInt(10), y = gy * 12 + r.nextInt(9), z = gz * 16 + r.nextInt(10);
            if (x != gx * 16 + offsetX(cell) || y != gy * 12 + offsetY(cell) || z != gz * 16 + offsetZ(cell)) {
                return "aquifer cell " + gx + ", " + gy + ", " + gz + " centre: CPU " + x + ", " + y + ", " + z + ", GPU " + (gx * 16 + offsetX(cell)) + ", "
                    + (gy * 12 + offsetY(cell)) + ", " + (gz * 16 + offsetZ(cell));
            }
            Aquifer.FluidStatus s = statuses.ga2$getAquiferStatus(BlockPos.asLong(x, y, z));
            var fs = (FluidStatusAccessor) (Object) s;
            if (fs.ga2$fluidLevel() != level(cell) || fs.ga2$fluidType() != fluid(cell)) {
                return "aquifer cell " + gx + ", " + gy + ", " + gz + ": CPU " + fs.ga2$fluidLevel() + " " + fs.ga2$fluidType() + ", GPU " + level(cell) + " " + fluid(cell);
            }
        }
        return null;
    }

    /** The same data with a separate aquifer, for a chunk copy. */
    public GpuTerrain copy() {
        return new GpuTerrain(spec, data, cx, cz);
    }

    /** {@code NoiseChunk.preliminarySurfaceLevel}. */
    public int preliminarySurfaceLevel(int x, int z) {
        int qx = QuartPos.fromBlock(x) - cx * 4, qz = QuartPos.fromBlock(z) - cz * 4;
        if (qx >= 0 && qx < 5 && qz >= 0 && qz < 5) {
            return data[2 * spec.cellsPerChunk + qx * 5 + qz];
        }
        LEVEL_FALLBACKS.increment();
        int bx = QuartPos.toBlock(QuartPos.fromBlock(x)), bz = QuartPos.toBlock(QuartPos.fromBlock(z));
        DensityFunction levels = spec.walk ? spec.walkLevels() : spec.levels;
        for (int y = spec.minY + spec.height; y >= spec.minY; y -= spec.cellHeight) {
            if (levels.compute(new DensityFunction.SinglePointContext(bx, y, bz)) > 0.390625) {
                return y;
            }
        }
        return Integer.MAX_VALUE;
    }

    /** The chunk's aquifer, as the NoiseChunk's aquifer would be. Created once. */
    public Aquifer aquifer() {
        GpuAquifer a = aquifer;
        if (a == null) {
            aquifer = a = new GpuAquifer(this);
        }
        return a;
    }

    /** The aquifer's fluid update flag after the noise fill, as the fill's last block left it. */
    boolean fillFlag() {
        return data[data.length - 1] != 0;
    }

    int level(int cell) {
        return data[2 * cell];
    }

    BlockState fluid(int cell) {
        return (data[2 * cell + 1] & 1) != 0 ? Blocks.LAVA.defaultBlockState() : spec.fluid;
    }

    int offsetX(int cell) {
        return data[2 * cell + 1] >> 8 & 15;
    }

    int offsetY(int cell) {
        return data[2 * cell + 1] >> 12 & 15;
    }

    int offsetZ(int cell) {
        return data[2 * cell + 1] >> 16 & 15;
    }
}
