package dev.denismasterherobrine.generatoraccelerator2.dfc.codegen;

import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.BlendedSpec;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.NoiseSpec;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Fills one {@code CacheAllInCell} value array for a whole cell, in vanilla's {@code fillAllDirectly}
 * order (y descending, then x, then z). {@code interpolated} markers become trilinear reads of the
 * eight corner values the binding copies into {@code corners[root * 8 + i]} before the call, in
 * the order {@code noise000, noise100, noise010, noise110, noise001, noise101, noise011, noise111}.
 * {@code beardActive} tells the program whether any structure piece can touch this cell.
 */
public abstract class CellProgram extends CompiledProgram {
    public static final String INTERNAL = "dev/denismasterherobrine/generatoraccelerator2/dfc/codegen/CellProgram";
    public static final String FILL_DESC = Names.runtimeDesc("(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;[D[D[DIIIIII)V");

    /**
     * Per scratch slot, the block whose value the slot holds. A stamp equal to {@link #epoch} means the value is current.
     * A shared value computed for one block is reused within that block only. Replaces filling the per-block slots with
     * NaN before every block.
     */
    public final int[] stamps;
    /** The current block, advanced before every block ({@link #advance}). */
    public int epoch = 1;

    public CellProgram(NoiseSpec[] specs, BlendedSpec[] blended, DensityFunction[] externs, SplineRt[] splines, Object[] states, int scratchSize, int unused) {
        super(specs, blended, externs, splines, states, scratchSize);
        this.stamps = new int[Math.max(1, scratchSize)];
    }

    /** Moves to the next block. Every slot's value from an earlier block becomes stale. Stamps start over after 2^32 blocks. */
    public static void advance(CellProgram program) {
        if (++program.epoch == 0) {
            java.util.Arrays.fill(program.stamps, 0);
            program.epoch = 1;
        }
    }

    public abstract void fillCell(DensityFunction.FunctionContext ctx, double[] scratch, double[] values, double[] corners,
                                  int cellStartX, int cellStartY, int cellStartZ, int cellWidth, int cellHeight, int beardActive);
}
