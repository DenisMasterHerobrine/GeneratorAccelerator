package dev.denismasterherobrine.generatoraccelerator2.dfc.pipeline;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.dfc.DfcConfig;
import dev.denismasterherobrine.generatoraccelerator2.dfc.analysis.CellCacheOnce;
import dev.denismasterherobrine.generatoraccelerator2.dfc.analysis.RefCount;
import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.CellTemplate;
import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.ColumnTemplate;
import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.DfcCompileException;
import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.Emitter;
import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.PointTemplate;
import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.ProgramCompiler;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.BorderColumnCache;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.BoundSampler;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.IrBuilder;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.Node;
import dev.denismasterherobrine.generatoraccelerator2.dfc.program.Interpreter;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.ProgramSet;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseRouter;
import org.slf4j.Logger;

/** Entry point: lowers a {@link NoiseRouter} and its {@link Climate.Sampler} into a {@link ProgramSet}. */
public final class Compiler {
    public static final Logger LOGGER = Ga2Log.get("DFC");

    private Compiler() {}

    public static ProgramSet compile(NoiseRouter router, Climate.Sampler sampler) {
        long t0 = System.nanoTime();
        CoverageReport report = new CoverageReport();
        IrBuilder builder = new IrBuilder(report);

        Map<String, Node> routerRoots = new LinkedHashMap<>();
        routerField(builder, routerRoots, "barrier", router.barrierNoise());
        routerField(builder, routerRoots, "fluid_level_floodedness", router.fluidLevelFloodednessNoise());
        routerField(builder, routerRoots, "fluid_level_spread", router.fluidLevelSpreadNoise());
        routerField(builder, routerRoots, "lava", router.lavaNoise());
        routerField(builder, routerRoots, "temperature", router.temperature());
        routerField(builder, routerRoots, "vegetation", router.vegetation());
        routerField(builder, routerRoots, "continents", router.continents());
        routerField(builder, routerRoots, "erosion", router.erosion());
        routerField(builder, routerRoots, "depth", router.depth());
        routerField(builder, routerRoots, "ridges", router.ridges());
        routerField(builder, routerRoots, "initial_density_without_jaggedness", router.initialDensityWithoutJaggedness());
        routerField(builder, routerRoots, "final_density", router.finalDensity());
        routerField(builder, routerRoots, "vein_toggle", router.veinToggle());
        routerField(builder, routerRoots, "vein_ridged", router.veinRidged());
        routerField(builder, routerRoots, "vein_gap", router.veinGap());

        Map<String, Node> samplerRoots = new LinkedHashMap<>();
        if (sampler != null) {
            routerField(builder, samplerRoots, "sampler.temperature", sampler.temperature());
            routerField(builder, samplerRoots, "sampler.humidity", sampler.humidity());
            routerField(builder, samplerRoots, "sampler.continentalness", sampler.continentalness());
            routerField(builder, samplerRoots, "sampler.erosion", sampler.erosion());
            routerField(builder, samplerRoots, "sampler.depth", sampler.depth());
            routerField(builder, samplerRoots, "sampler.weirdness", sampler.weirdness());
        }

        Interpreter interpreter = new Interpreter(builder.specs(), builder.blendedSpecs());

        ColumnTemplate column = null;
        List<Node.Marker> interpolated = collectInterpolated(routerRoots.values());
        if (!interpolated.isEmpty() && DfcConfig.columnPrograms()) {
            try {
                column = ProgramCompiler.compileColumn("router", interpolated, builder.specs(), builder.blendedSpecs());
                for (Node.Marker m : column.statefulMarkers) {
                    report.statefulMarker(m.vanilla(), "final_density", m.type().name());
                }
            } catch (DfcCompileException e) {
                if (DfcConfig.failOnCodegenError()) {
                    throw e;
                }
                LOGGER.error("column program generation failed -> vanilla chunk corner noise", e);
                report.interpreterRouted("column", e.getMessage());
            }
        }

        List<CellTemplate> cells = new ArrayList<>();
        if (DfcConfig.cellPrograms()) {
            Map<Node.Marker, Integer> lerpIndex = new IdentityHashMap<>();
            for (int i = 0; i < interpolated.size(); i++) {
                lerpIndex.put(interpolated.get(i), i);
            }
            try {
                // The chunk's own cache: cacheAllInCell(add(finalDensity, beardifier)), built in NoiseChunk's constructor.
                Node finalDensity = routerRoots.get("final_density");
                Node beard = new Node.Beardifier(DensityFunctions.BeardifierMarker.INSTANCE, 0.0, 0.0);
                Node mainRoot = new Node.Ap2(Node.Ap2Type.ADD, finalDensity, beard, finalDensity.min(), finalDensity.max());
                // A cache_once that vanilla evaluates point by point inside a cell shares one value across the cell (see
                // CellCacheOnce). The cell programs do not reproduce that, so such a cell keeps vanilla's fill.
                if (CellCacheOnce.onPointPath(mainRoot)) {
                    report.interpreterRouted("cell", "final_density has a cache_once evaluated point by point inside the cell");
                } else {
                    cells.add(ProgramCompiler.compileCell("final_density", mainRoot, null, lerpIndex, builder.specs(), builder.blendedSpecs()));
                }
                for (Node.Marker m : collectMarkers(routerRoots.values(), Node.MarkerType.CACHE_ALL_IN_CELL)) {
                    if (CellCacheOnce.onPointPath(m.child())) {
                        report.interpreterRouted("cell", "cache_all_in_cell has a cache_once evaluated point by point inside the cell");
                        continue;
                    }
                    cells.add(ProgramCompiler.compileCell("cache_all_in_cell", m.child(), m, lerpIndex, builder.specs(), builder.blendedSpecs()));
                }
                for (CellTemplate t : cells) {
                    for (Node.Marker m : t.statefulMarkers) {
                        report.statefulMarker(m.vanilla(), "final_density(cell)", m.type().name(), whyStateful(m));
                    }
                }
            } catch (DfcCompileException e) {
                if (DfcConfig.failOnCodegenError()) {
                    throw e;
                }
                LOGGER.error("cell program generation failed -> vanilla per-block density", e);
                report.interpreterRouted("cell", e.getMessage());
                cells.clear();
            }
        }

        PointTemplate chunkSampler = null;
        if (DfcConfig.pointPrograms()) {
            try {
                // The chunk sampler is built from the chunk-wrapped router fields (NoiseChunk.cachedClimateSampler), so the
                // program must follow the chunk marker policy. Its roots are the router's own climate fields.
                List<Node> climate = List.of(routerRoots.get("temperature"), routerRoots.get("vegetation"), routerRoots.get("continents"),
                    routerRoots.get("erosion"), routerRoots.get("depth"), routerRoots.get("ridges"));
                chunkSampler = ProgramCompiler.compilePointTemplate("chunk-climate", climate, builder.specs(), builder.blendedSpecs(), Emitter.Mode.COLUMN);
            } catch (DfcCompileException e) {
                if (DfcConfig.failOnCodegenError()) {
                    throw e;
                }
                LOGGER.error("climate sampler generation failed -> vanilla biome sampling", e);
                report.interpreterRouted("climate", e.getMessage());
            }
        }

        // The RandomState's own sampler serves stronghold rings, structure biome checks, the spawn search and /locate. Its
        // fields are the climate fields with every marker unwrapped, so the program evaluates markers transparently.
        PointTemplate globalSampler = null;
        if (DfcConfig.pointPrograms() && BoundSampler.GLOBAL && samplerRoots.size() == 6) {
            try {
                globalSampler = ProgramCompiler.compilePointTemplate("global-climate", List.copyOf(samplerRoots.values()), builder.specs(), builder.blendedSpecs(), Emitter.Mode.POINT);
            } catch (DfcCompileException e) {
                if (DfcConfig.failOnCodegenError()) {
                    throw e;
                }
                LOGGER.error("global climate sampler generation failed -> vanilla path", e);
                report.interpreterRouted("climate-global", e.getMessage());
            }
        }

        // The surface level walk (NoiseChunk.computePreliminarySurfaceLevel) evaluates the chunk-wrapped surface level function at
        // points up to three chunks away. So its program keeps the chunk marker policy but evaluates flat caches as their
        // children. This requires every flat cache in the function to read x and z only.
        dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.SurfaceLevels.Templates surfaceLevels = null;
        Node levels = routerRoots.get("initial_density_without_jaggedness");
        List<Node.Marker> levelFlats = levels == null ? null : dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.SurfaceLevels.flatCaches(levels);
        if (DfcConfig.pointPrograms() && dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.SurfaceLevels.ENABLED && levelFlats != null) {
            try {
                PointTemplate walk = ProgramCompiler.compilePointTemplate("surface-levels", List.of(levels), builder.specs(), builder.blendedSpecs(), Emitter.Mode.COLUMN);
                PointTemplate flats = null;
                java.util.Map<net.minecraft.world.level.levelgen.DensityFunction, Integer> flatIndex = new java.util.IdentityHashMap<>();
                if (!levelFlats.isEmpty()) {
                    List<Node> children = new java.util.ArrayList<>();
                    for (Node.Marker m : levelFlats) {
                        flatIndex.put(m.vanilla(), children.size());
                        children.add(m.child());
                    }
                    flats = ProgramCompiler.compilePointTemplate("surface-flats", children, builder.specs(), builder.blendedSpecs(), Emitter.Mode.POINT);
                }
                surfaceLevels = new dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.SurfaceLevels.Templates(walk, flats, flatIndex);
            } catch (DfcCompileException e) {
                if (DfcConfig.failOnCodegenError()) {
                    throw e;
                }
                LOGGER.error("surface level program generation failed -> vanilla surface levels", e);
                report.interpreterRouted("surface-levels", e.getMessage());
            }
        }

        BorderColumnCache borderCache = column != null && DfcConfig.borderColumnCache() ? new BorderColumnCache(8192) : null;
        long nanos = System.nanoTime() - t0;
        ProgramSet set = new ProgramSet(routerRoots, samplerRoots, builder.specs(), builder.blendedSpecs(), interpreter, column, List.copyOf(cells),
            chunkSampler, globalSampler, surfaceLevels, borderCache, report, builder.internedNodeCount(), nanos);

        report.mergeInto(CoverageReport.global());
        LOGGER.info("lowered {} router + {} sampler roots -> {} nodes, {} noise specs, {} blended; column: {} roots, {} externs; {} cell programs ({} ms)",
            routerRoots.size(), samplerRoots.size(), builder.internedNodeCount(), builder.specs().size(), builder.blendedSpecs().size(),
            column == null ? 0 : column.rootCount(), column == null ? 0 : column.externTemplates.length, cells.size(), nanos / 1_000_000L);
        report.logSummary(LOGGER, "coverage");
        if (report.hasFallbacks() && DfcConfig.failOnUnsupported()) {
            throw new IllegalStateException("[GA2/DFC] strict mode: density functions without lowering: " + String.join("; ", report.lines()));
        }
        return set;
    }

    private static void routerField(IrBuilder builder, Map<String, Node> out, String name, DensityFunction fn) {
        out.put(name, builder.build(name, fn));
    }

    /** Why a cell program keeps a marker's wrapper, for the coverage report (empty when there is nothing to say). */
    private static String whyStateful(Node.Marker m) {
        if (m.type() != Node.MarkerType.CACHE_2D) {
            return "";
        }
        String y = new dev.denismasterherobrine.generatoraccelerator2.dfc.analysis.CoordDeps(true).firstReadingY(m.child());
        if (y != null) {
            return "its child reads y (" + y + ")";
        }
        return CellCacheOnce.reachesCacheOnce(m.child()) ? "its child holds a cache_once"
            : "child " + Names.mojangSimpleName(m.child().getClass()) + " not reached by the column caches";
    }

    /** All distinct {@code interpolated} markers reachable from the roots, in first-visit order. */
    static List<Node.Marker> collectInterpolated(Iterable<Node> roots) {
        return collectMarkers(roots, Node.MarkerType.INTERPOLATED);
    }

    static List<Node.Marker> collectMarkers(Iterable<Node> roots, Node.MarkerType type) {
        List<Node.Marker> out = new ArrayList<>();
        Map<Node, Boolean> seen = new IdentityHashMap<>();
        for (Node r : roots) {
            collect(r, seen, out, type);
        }
        return out;
    }

    private static void collect(Node n, Map<Node, Boolean> seen, List<Node.Marker> out, Node.MarkerType type) {
        if (seen.put(n, Boolean.TRUE) != null) {
            return;
        }
        if (n instanceof Node.Marker m && m.type() == type) {
            out.add(m);
        }
        for (Node c : RefCount.children(n)) {
            collect(c, seen, out, type);
        }
    }
}
