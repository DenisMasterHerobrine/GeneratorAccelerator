package dev.denismasterherobrine.generatoraccelerator2.gpu;

import dev.denismasterherobrine.generatoraccelerator2.aquifer.LiquidRegions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import javax.annotation.Nullable;

/**
 * A chunk's {@code NoiseBasedAquifer}, with each aquifer cell's centre and {@code FluidStatus} taken from the GPU
 * ({@link GpuTerrain}). {@code computeSubstance} and {@code calculatePressure} are transcribed from vanilla, but read the cells
 * instead of computing them. A carver's queries get the same answers as from the NoiseChunk's aquifer, including the fluid
 * update flag. The statuses match what that aquifer computes, and the search, similarity and pressure are vanilla's.
 */
public final class GpuAquifer implements Aquifer {
    private static final double FLOWING_UPDATE_SIMILARITY = similarity(10 * 10, 12 * 12);
    private static final BlockState LAVA = Blocks.LAVA.defaultBlockState();

    private final GpuTerrain t;
    private final int minGridX, minGridY, minGridZ;
    private final Aquifer.FluidStatus[] statuses;
    private boolean shouldScheduleFluidUpdate;

    GpuAquifer(GpuTerrain t) {
        this.t = t;
        this.minGridX = t.cx - 1;
        this.minGridZ = t.cz - 1;
        this.minGridY = t.spec.minGridY;
        this.statuses = new Aquifer.FluidStatus[t.spec.cellsPerChunk];
        // The NoiseChunk's aquifer keeps the flag set by its last computation in the fill. A carver reads the flag at the lava level,
        // before computing.
        this.shouldScheduleFluidUpdate = t.fillFlag();
    }

    private int index(int gx, int gy, int gz) {
        return ((gy - minGridY) * 3 + (gz - minGridZ)) * 3 + (gx - minGridX);
    }

    private Aquifer.FluidStatus status(int cell) {
        Aquifer.FluidStatus s = statuses[cell];
        if (s == null) {
            statuses[cell] = s = new Aquifer.FluidStatus(t.level(cell), t.fluid(cell));
        }
        return s;
    }

    @Override
    public BlockState computeSubstance(DensityFunction.FunctionContext context, double substance) {
        return substanceAt(context.blockX(), context.blockY(), context.blockZ(), context, substance);
    }

    /**
     * {@link #computeSubstance} at block {@code i, j, k}. {@code context} is that block's, or null. The context is read only to
     * sample the barrier noise, which few blocks need. Without one, a context for the block is made then, as the carver would
     * have made it for the call.
     */
    public BlockState substanceAt(int i, int j, int k, @Nullable DensityFunction.FunctionContext context, double substance) {
        BlockState state = substance0(i, j, k, context, substance);
        // Better Caves' step at computeSubstance's return, as the chunk's NoiseChunk aquifer would run it
        return t.liquids != null ? LiquidRegions.apply(t.liquids, i, j, k, state) : state;
    }

    private BlockState substance0(int i, int j, int k, @Nullable DensityFunction.FunctionContext context, double substance) {
        if (substance > 0.0) {
            this.shouldScheduleFluidUpdate = false;
            return null;
        }
        if (j > t.skipSamplingAboveY()) {
            // vanilla samples no cell above its chunk's surfaces
            this.shouldScheduleFluidUpdate = false;
            return t.spec.picker.computeFluid(i, j, k).at(j);
        }
        if (t.spec.globalLava(i, j, k)) {
            this.shouldScheduleFluidUpdate = false;
            return LAVA;
        }
        int l = Math.floorDiv(i - 5, 16);
        int i1 = Math.floorDiv(j + 1, 12);
        int j1 = Math.floorDiv(k - 5, 16);
        int k1 = Integer.MAX_VALUE;
        int l1 = Integer.MAX_VALUE;
        int i2 = Integer.MAX_VALUE;
        int m4 = Integer.MAX_VALUE;
        int c1 = 0, c2 = 0, c3 = 0, c4 = 0;
        for (int i3 = 0; i3 <= 1; i3++) {
            for (int j3 = -1; j3 <= 1; j3++) {
                for (int k3 = 0; k3 <= 1; k3++) {
                    int gx = l + i3;
                    int gy = i1 + j3;
                    int gz = j1 + k3;
                    int cell = index(gx, gy, gz);
                    int dx = gx * 16 + t.offsetX(cell) - i;
                    int dy = gy * 12 + t.offsetY(cell) - j;
                    int dz = gz * 16 + t.offsetZ(cell) - k;
                    int l5 = dx * dx + dy * dy + dz * dz;
                    if (k1 >= l5) {
                        c4 = c3;
                        c3 = c2;
                        c2 = c1;
                        c1 = cell;
                        m4 = i2;
                        i2 = l1;
                        l1 = k1;
                        k1 = l5;
                    } else if (l1 >= l5) {
                        c4 = c3;
                        c3 = c2;
                        c2 = cell;
                        m4 = i2;
                        i2 = l1;
                        l1 = l5;
                    } else if (i2 >= l5) {
                        c4 = c3;
                        c3 = cell;
                        m4 = i2;
                        i2 = l5;
                    } else if (m4 >= l5) {
                        c4 = cell;
                        m4 = l5;
                    }
                }
            }
        }
        Aquifer.FluidStatus s1 = status(c1);
        double d1 = similarity(k1, l1);
        BlockState state = s1.at(j);
        if (d1 <= 0.0) {
            this.shouldScheduleFluidUpdate = d1 >= FLOWING_UPDATE_SIMILARITY && !s1.equals(status(c2));
            return state;
        }
        if (state.is(Blocks.WATER) && t.spec.globalLava(i, j - 1, k)) {
            this.shouldScheduleFluidUpdate = true;
            return state;
        }
        this.barrier = Double.NaN;
        this.barrierContext = context;
        this.bx = i;
        this.by = j;
        this.bz = k;
        double d2 = d1 * pressure(c1, c2);
        if (substance + d2 > 0.0) {
            this.shouldScheduleFluidUpdate = false;
            return null;
        }
        double d0 = similarity(k1, i2);
        if (d0 > 0.0) {
            double d3 = d1 * d0 * pressure(c1, c3);
            if (substance + d3 > 0.0) {
                this.shouldScheduleFluidUpdate = false;
                return null;
            }
        }
        double d4 = similarity(l1, i2);
        if (d4 > 0.0) {
            double d5 = d1 * d4 * pressure(c2, c3);
            if (substance + d5 > 0.0) {
                this.shouldScheduleFluidUpdate = false;
                return null;
            }
        }
        Aquifer.FluidStatus s2 = status(c2);
        Aquifer.FluidStatus s3 = status(c3);
        this.shouldScheduleFluidUpdate = !s1.equals(s2)
            || d4 >= FLOWING_UPDATE_SIMILARITY && !s2.equals(s3)
            || d0 >= FLOWING_UPDATE_SIMILARITY && !s1.equals(s3)
            || d0 >= FLOWING_UPDATE_SIMILARITY && similarity(k1, m4) >= FLOWING_UPDATE_SIMILARITY && !s1.equals(status(c4));
        return state;
    }

    @Override
    public boolean shouldScheduleFluidUpdate() {
        return this.shouldScheduleFluidUpdate;
    }

    private static double similarity(int a, int b) {
        return 1.0 - (double) Math.abs(b - a) / 25.0;
    }

    /** The block {@link #pressure} computes at, its context if the caller had one, and the barrier noise there once sampled (NaN before). */
    private int bx, by, bz;
    @Nullable
    private DensityFunction.FunctionContext barrierContext;
    private double barrier;

    /** {@code NoiseBasedAquifer.calculatePressure} for cells {@code a} and {@code b} at the current block. */
    private double pressure(int a, int b) {
        int i = this.by;
        BlockState sa = status(a).at(i);
        BlockState sb = status(b).at(i);
        if ((!sa.is(Blocks.LAVA) || !sb.is(Blocks.WATER)) && (!sa.is(Blocks.WATER) || !sb.is(Blocks.LAVA))) {
            int la = t.level(a), lb = t.level(b);
            int j = Math.abs(la - lb);
            if (j == 0) {
                return 0.0;
            }
            double d0 = 0.5 * (double) (la + lb);
            double d1 = (double) i + 0.5 - d0;
            double d2 = (double) j / 2.0;
            double d9 = d2 - Math.abs(d1);
            double d10;
            if (d1 > 0.0) {
                double d11 = 0.0 + d9;
                d10 = d11 > 0.0 ? d11 / 1.5 : d11 / 2.5;
            } else {
                double d15 = 3.0 + d9;
                d10 = d15 > 0.0 ? d15 / 3.0 : d15 / 10.0;
            }
            double d12;
            if (!(d10 < -2.0) && !(d10 > 2.0)) {
                double d13 = this.barrier;
                if (Double.isNaN(d13)) {
                    DensityFunction.FunctionContext context = this.barrierContext;
                    double d14 = t.spec.barrier.compute(context != null ? context : new DensityFunction.SinglePointContext(this.bx, this.by, this.bz));
                    this.barrier = d14;
                    d12 = d14;
                } else {
                    d12 = d13;
                }
            } else {
                d12 = 0.0;
            }
            return 2.0 * (d12 + d10);
        }
        return 2.0;
    }
}
