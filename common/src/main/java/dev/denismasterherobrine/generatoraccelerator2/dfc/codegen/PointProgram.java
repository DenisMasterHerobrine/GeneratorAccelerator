package dev.denismasterherobrine.generatoraccelerator2.dfc.codegen;

import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.BlendedSpec;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.NoiseSpec;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Evaluates N roots at one block position. Generated subclasses implement {@link #computeAll}.
 * The scratch array holds lazily computed shared subexpressions and spline coordinates.
 */
public abstract class PointProgram extends CompiledProgram {
    public static final String INTERNAL = "dev/denismasterherobrine/generatoraccelerator2/dfc/codegen/PointProgram";
    public static final String COMPUTE_ALL_DESC = Names.runtimeDesc("(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;[D[D)V");
    public static final String COMPUTE_COLUMN_POINT_DESC = Names.runtimeDesc("(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;[D[DZ)V");

    public final int rootCount;

    public PointProgram(NoiseSpec[] specs, BlendedSpec[] blended, DensityFunction[] externs, SplineRt[] splines, Object[] states, int scratchSize, int rootCount) {
        super(specs, blended, externs, splines, states, scratchSize);
        this.rootCount = rootCount;
    }

    /** Fills {@code out[0..rootCount)} with every root's value at {@code ctx}. */
    public abstract void computeAll(DensityFunction.FunctionContext ctx, double[] scratch, double[] out);

    /**
     * As {@link #computeAll}, for a caller that keeps one scratch array per (x, z) column. With {@code hoistedValid}, the
     * scratch already holds this column's (x, z)-only values from an earlier call at the same (x, z). Only the per-point
     * values are then computed. The values are the same either way, because (x, z)-only subexpressions are pure.
     */
    public abstract void computeColumnPoint(DensityFunction.FunctionContext ctx, double[] scratch, double[] out, boolean hoistedValid);
}
