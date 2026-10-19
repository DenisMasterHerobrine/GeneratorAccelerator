package dev.denismasterherobrine.generatoraccelerator2.dfc.codegen;

import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.BlendedSpec;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.NoiseSpec;
import java.lang.invoke.MethodHandle;
import net.minecraft.world.level.levelgen.DensityFunction;

/** A compiled point program class plus the data needed to instantiate it (per chunk when it has chunk-level externs). */
public final class PointTemplate {
    private final MethodHandle ctor;
    private final NoiseSpec[] specs;
    private final BlendedSpec[] blended;
    private final SplineRt[] splines;
    private final StateDesc[] states;
    private final int scratchSize;
    private final int rootCount;
    public final DensityFunction[] externTemplates;

    PointTemplate(MethodHandle ctor, NoiseSpec[] specs, BlendedSpec[] blended, SplineRt[] splines, StateDesc[] states, int scratchSize, int rootCount, DensityFunction[] externTemplates) {
        this.ctor = ctor;
        this.specs = specs;
        this.blended = blended;
        this.splines = splines;
        this.states = states;
        this.scratchSize = scratchSize;
        this.rootCount = rootCount;
        this.externTemplates = externTemplates;
    }

    public int scratchSize() {
        return scratchSize;
    }

    public int rootCount() {
        return rootCount;
    }

    public PointProgram instantiate(DensityFunction[] externs) {
        try {
            return (PointProgram) ctor.invoke(specs, blended, externs, splines, StateDesc.instantiate(states, specs, blended), scratchSize, rootCount);
        } catch (Throwable t) {
            throw new DfcCompileException("could not instantiate point program", t);
        }
    }
}
