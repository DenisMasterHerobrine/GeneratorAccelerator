package dev.denismasterherobrine.generatoraccelerator2.dfc.codegen;

import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.BlendedSpec;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.NoiseSpec;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Evaluates every {@code interpolated} root of a router for all cell corners of one column.
 * Generated subclasses implement {@link #fillColumn}. {@code out[root][yIndex]} receives the value
 * at block y {@code (yIndex + cellNoiseMinY) * cellHeight}. This is exactly what vanilla's
 * {@code NoiseInterpolator.fillArray} writes into a slice.
 */
public abstract class ColumnProgram extends CompiledProgram {
    public static final String INTERNAL = "dev/denismasterherobrine/generatoraccelerator2/dfc/codegen/ColumnProgram";
    public static final String FILL_DESC = Names.runtimeDesc("(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;[D[[DIIIII)V");

    public final int rootCount;

    public ColumnProgram(NoiseSpec[] specs, BlendedSpec[] blended, DensityFunction[] externs, SplineRt[] splines, Object[] states, int scratchSize, int rootCount) {
        super(specs, blended, externs, splines, states, scratchSize);
        this.rootCount = rootCount;
    }

    public abstract void fillColumn(DensityFunction.FunctionContext ctx, double[] scratch, double[][] out,
                                    int blockX, int blockZ, int cellNoiseMinY, int cellHeight, int cellCountY);
}
