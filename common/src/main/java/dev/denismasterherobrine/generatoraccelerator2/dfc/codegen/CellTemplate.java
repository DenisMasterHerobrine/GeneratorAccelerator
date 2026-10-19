package dev.denismasterherobrine.generatoraccelerator2.dfc.codegen;

import dev.denismasterherobrine.generatoraccelerator2.dfc.analysis.CellBounds;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.BlendedSpec;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.Node;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.NoiseSpec;
import java.lang.invoke.MethodHandle;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.world.level.levelgen.DensityFunction;

/** A compiled cell program class for one cell-cache root, plus the data needed to instantiate it per chunk. */
public final class CellTemplate {
    private final MethodHandle ctor;
    private final NoiseSpec[] specs;
    private final BlendedSpec[] blended;
    private final SplineRt[] splines;
    private final StateDesc[] states;
    private final int scratchSize;
    public final DensityFunction[] externTemplates;
    /** The {@code cache_all_in_cell} marker this program fills, or {@code null} for the chunk's own final-density cache. */
    @Nullable public final Node.Marker marker;
    public final List<Node.Marker> statefulMarkers;
    /** How many {@code cache_2d} markers of x and z only the program keeps per column instead of asking their wrappers. */
    public final int columnCaches;
    /** How many other x/z-only subtrees the program computes once per column instead of at every block. */
    public final int columnValues;
    /**
     * Prototype of the bounds of the program's value over a cell, derived from the cell's corners. Each binding takes a
     * {@link CellBounds#fresh()} copy. Null when the tree has nodes the bounds do not cover.
     */
    @Nullable public final CellBounds bounds;

    CellTemplate(MethodHandle ctor, NoiseSpec[] specs, BlendedSpec[] blended, SplineRt[] splines, StateDesc[] states, int scratchSize,
                 DensityFunction[] externTemplates, @Nullable Node.Marker marker, List<Node.Marker> statefulMarkers, int columnCaches, int columnValues, @Nullable CellBounds bounds) {
        this.ctor = ctor;
        this.specs = specs;
        this.blended = blended;
        this.splines = splines;
        this.states = states;
        this.scratchSize = scratchSize;
        this.externTemplates = externTemplates;
        this.marker = marker;
        this.statefulMarkers = List.copyOf(statefulMarkers);
        this.columnCaches = columnCaches;
        this.columnValues = columnValues;
        this.bounds = bounds;
    }

    public int scratchSize() {
        return scratchSize;
    }

    public CellProgram instantiate(DensityFunction[] externs) {
        try {
            return (CellProgram) ctor.invoke(specs, blended, externs, splines, StateDesc.instantiate(states, specs, blended), scratchSize, 0);
        } catch (Throwable t) {
            throw new DfcCompileException("could not instantiate cell program", t);
        }
    }
}
