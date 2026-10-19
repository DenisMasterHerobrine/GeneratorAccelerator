package dev.denismasterherobrine.generatoraccelerator2.dfc.codegen;

import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.BlendedSpec;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.NoiseSpec;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Base class of every generated program. The generated hidden subclass only adds methods. All
 * data it needs lives in these final arrays, so one compiled class can be instantiated for any
 * {@code RandomState} (or chunk) with the same structure.
 */
public abstract class CompiledProgram {
    public static final String INTERNAL = "dev/denismasterherobrine/generatoraccelerator2/dfc/codegen/CompiledProgram";
    public static final String CTOR_DESC = Names.runtimeDesc("([Ldev/denismasterherobrine/generatoraccelerator2/dfc/ir/NoiseSpec;"
        + "[Ldev/denismasterherobrine/generatoraccelerator2/dfc/ir/BlendedSpec;"
        + "[Lnet/minecraft/world/level/levelgen/DensityFunction;"
        + "[Ldev/denismasterherobrine/generatoraccelerator2/dfc/codegen/SplineRt;I)V");

    protected final NoiseSpec[] specs;
    protected final BlendedSpec[] blended;
    protected final DensityFunction[] externs;
    protected final SplineRt[] splines;
    /** Per-instance column kernel states ({@code NoiseSpec.Column} / {@code BlendedSpec.Column}). Null entries for programs without them. */
    protected final Object[] states;
    /** Number of scratch slots the program needs. Callers pass a {@code double[]} at least this long. */
    public final int scratchSize;

    public CompiledProgram(NoiseSpec[] specs, BlendedSpec[] blended, DensityFunction[] externs, SplineRt[] splines, Object[] states, int scratchSize) {
        this.specs = specs;
        this.blended = blended;
        this.externs = externs;
        this.splines = splines;
        this.states = states;
        this.scratchSize = scratchSize;
    }

    public DensityFunction[] externs() {
        return externs;
    }
}
