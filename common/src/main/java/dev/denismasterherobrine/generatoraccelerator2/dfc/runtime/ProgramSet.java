package dev.denismasterherobrine.generatoraccelerator2.dfc.runtime;

import dev.denismasterherobrine.generatoraccelerator2.dfc.analysis.SkyBounds;
import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.CellTemplate;
import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.ColumnTemplate;
import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.PointTemplate;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.BlendedSpec;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.Node;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.NoiseSpec;
import dev.denismasterherobrine.generatoraccelerator2.dfc.pipeline.CoverageReport;
import dev.denismasterherobrine.generatoraccelerator2.dfc.program.Interpreter;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Everything the compiler produced for one {@code RandomState}: the IR of every router and
 * climate-sampler field, the noise spec tables, a reference interpreter over them, the compiled
 * column template (null when the router has no interpolated functions), the cell templates
 * (first entry is the chunk's own final-density cache), the chunk climate sampler template, the
 * RandomState's own climate sampler template, the shared border column cache and the coverage
 * report of that compile. Immutable after construction.
 */
public record ProgramSet(
    Map<String, Node> routerRoots,
    Map<String, Node> samplerRoots,
    List<NoiseSpec> specs,
    List<BlendedSpec> blendedSpecs,
    Interpreter interpreter,
    @Nullable ColumnTemplate column,
    List<CellTemplate> cells,
    @Nullable PointTemplate chunkSampler,
    @Nullable PointTemplate globalSampler,
    /** {@code initial_density_without_jaggedness} for the surface level walk ({@link SurfaceLevels}), or null. */
    @Nullable SurfaceLevels.Templates surfaceLevels,
    @Nullable BorderColumnCache borderCache,
    CoverageReport report,
    int internedNodes,
    long compileNanos,
    /** Other mods' samplers the programs call by index. {@link ExternSamplers} holds them weakly, so this set keeps them. */
    List<ExternSamplers.Sampler> externSamplers,
    /** Bounds of the interpolated roots over a column's upper corners, for the noise fill's sky corners, or null. */
    @Nullable SkyBounds sky
) {
    public Node root(String name) {
        Node n = routerRoots.get(name);
        return n != null ? n : samplerRoots.get(name);
    }
}
