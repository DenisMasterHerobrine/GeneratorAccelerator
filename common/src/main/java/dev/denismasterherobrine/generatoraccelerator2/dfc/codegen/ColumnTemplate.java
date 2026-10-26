package dev.denismasterherobrine.generatoraccelerator2.dfc.codegen;

import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.BlendedSpec;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.Node;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.NoiseSpec;
import java.lang.invoke.MethodHandle;
import java.util.List;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

/**
 * A compiled column program class plus the RandomState-level data needed to instantiate it for a
 * chunk. {@link #externTemplates} are RandomState-level objects (cache markers, the beardifier
 * marker, blend markers, mod nodes). Each chunk resolves them to its own instances.
 */
public final class ColumnTemplate {
    private final MethodHandle ctor;
    private final NoiseSpec[] specs;
    private final BlendedSpec[] blended;
    private final SplineRt[] splines;
    private final StateDesc[] states;
    private final int scratchSize;
    public final DensityFunction[] externTemplates;
    /** The {@code interpolated} markers whose children are the roots, in root order. */
    public final List<Node.Marker> rootMarkers;
    /** Markers evaluated through their vanilla wrapper because they carry per-chunk state. */
    public final List<Node.Marker> statefulMarkers;
    /**
     * Whether a root reads the beardifier. This happens when the beardifier sits inside {@code interpolated}, as in
     * Terralith. A column then depends on the chunk's own structure pieces. Neighbouring chunks may see different pieces at
     * their shared border, because each chunk sees the starts it references. So the column's values do not depend on the
     * position alone.
     */
    public final boolean readsBeardifier;

    ColumnTemplate(MethodHandle ctor, NoiseSpec[] specs, BlendedSpec[] blended, SplineRt[] splines, StateDesc[] states, int scratchSize,
                   DensityFunction[] externTemplates, List<Node.Marker> rootMarkers, List<Node.Marker> statefulMarkers) {
        this.ctor = ctor;
        this.specs = specs;
        this.blended = blended;
        this.splines = splines;
        this.states = states;
        this.scratchSize = scratchSize;
        this.externTemplates = externTemplates;
        this.rootMarkers = rootMarkers;
        this.statefulMarkers = List.copyOf(statefulMarkers);
        boolean beard = false;
        for (DensityFunction f : externTemplates) {
            beard |= f instanceof DensityFunctions.BeardifierOrMarker;
        }
        this.readsBeardifier = beard;
    }

    public int rootCount() {
        return rootMarkers.size();
    }

    public int scratchSize() {
        return scratchSize;
    }

    public ColumnProgram instantiate(DensityFunction[] externs) {
        try {
            return (ColumnProgram) ctor.invoke(specs, blended, externs, splines, StateDesc.instantiate(states, specs, blended), scratchSize, rootMarkers.size());
        } catch (Throwable t) {
            throw new DfcCompileException("could not instantiate column program", t);
        }
    }
}
