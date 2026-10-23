package dev.denismasterherobrine.generatoraccelerator2.dfc.runtime;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.dfc.DfcConfig;
import dev.denismasterherobrine.generatoraccelerator2.dfc.analysis.CellBounds;
import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.CellProgram;
import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.CellTemplate;
import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.ColumnProgram;
import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.ColumnTemplate;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.Node;
import dev.denismasterherobrine.generatoraccelerator2.mixin.dfc.NoiseChunkAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.dfc.NoiseInterpolatorAccessor;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * Per-{@link NoiseChunk} state of the compiled programs: the column program bound to this chunk's
 * cache wrappers, the cell programs bound to its cell caches, the interpolator-to-root mapping,
 * the beardifier culler and the scratch buffers. One chunk is only ever filled by one thread,
 * like vanilla's own interpolator state.
 */
public final class ChunkBinding {
    private static final java.util.concurrent.atomic.AtomicInteger DIAG_LOGS = new java.util.concurrent.atomic.AtomicInteger();

    private final NoiseChunk chunk;
    private final NoiseChunkAccessor acc;
    private final ColumnProgram column;
    private final Map<NoiseChunk.NoiseInterpolator, Integer> rootIndex = new IdentityHashMap<>();
    private final NoiseChunk.NoiseInterpolator[] interpolatorByRoot;
    private final double[] columnScratch;
    private final double[][] out;
    private final int cellCountY;
    private final int cellNoiseMinY;
    private final int cellHeight;
    private final int cellWidth;
    private int lastColumnX = Integer.MIN_VALUE;
    private int lastColumnZ = Integer.MIN_VALUE;
    private double[][] current;
    private BorderColumnCache borderCache;
    public long columnsFilled;

    private final Map<DensityFunction, Integer> cellIndex = new IdentityHashMap<>();
    private final CellProgram[] cells;
    /** One scratch per cell program. A program keeps its column caches in its scratch from one cell to the next. */
    private final double[][] cellScratch;
    private final double[] corners;
    private final BeardCuller culler;
    /** The beardifier must be asked for every cell (it is not known to give exactly 0 away from vanilla pieces). */
    private final boolean beardEverywhere;
    public long cellsFilled;
    public long cellsWithBeard;

    /** Per cell program: bounds of its value over a cell from the corners, or null ({@link CellBounds}). */
    private final CellBounds[] bounds;
    private final boolean blenderEmpty;
    /** While the noise fill classifies cells: the final density cell cache's values, and the aquifer's whole-cell air test. */
    private double[] classifyValues;
    private CellSkipper skipper;
    /** The classified cell whose values were left unfilled, for {@link #fillCurrentCell}. */
    private CellProgram pendingProgram;
    private int pendingIndex;
    private double[] pendingValues;
    private int pendingX0;
    private int pendingY0;
    private int pendingZ0;
    public long cellsSky;
    public long cellsSolid;

    private ChunkBinding(NoiseChunk chunk, ColumnProgram column, int columnRoots, int columnScratch, CellProgram[] cells, int[] cellScratch, CellBounds[] bounds,
                         BeardCuller culler, boolean beardEverywhere) {
        this.chunk = chunk;
        this.acc = (NoiseChunkAccessor) chunk;
        this.column = column;
        this.cellCountY = acc.ga2$cellCountY();
        this.cellNoiseMinY = acc.ga2$cellNoiseMinY();
        this.cellHeight = acc.ga2$cellHeight();
        this.cellWidth = acc.ga2$cellWidth();
        this.interpolatorByRoot = new NoiseChunk.NoiseInterpolator[columnRoots];
        this.columnScratch = new double[Math.max(1, columnScratch)];
        this.out = new double[columnRoots][cellCountY + 1];
        this.cells = cells;
        this.cellScratch = new double[cells.length][];
        for (int i = 0; i < cells.length; i++) {
            this.cellScratch[i] = new double[Math.max(1, cellScratch[i])];
        }
        this.corners = new double[Math.max(8, columnRoots * 8)];
        this.bounds = bounds;
        this.blenderEmpty = chunk.getBlender() == Blender.empty();
        this.culler = culler;
        this.beardEverywhere = beardEverywhere;
    }

    /**
     * Binds the RandomState's compiled programs to a freshly constructed NoiseChunk.
     *
     * @param baseRouter    the RandomState-level router
     * @param wrappedRouter the same router after {@code NoiseChunk.wrap} (memoised, so no new wrappers are created)
     */
    public static ChunkBinding bind(NoiseChunk chunk, ProgramSet set, NoiseRouter baseRouter, NoiseRouter wrappedRouter) {
        return bind(chunk, set, MarkerPairing.of(baseRouter, wrappedRouter));
    }

    /**
     * {@link #bind(NoiseChunk, ProgramSet, NoiseRouter, NoiseRouter)} with the chunk's router pairing already made
     * ({@link MarkerPairing#of}). The pairing gains the chunk's beardifier for the beardifier marker.
     */
    public static ChunkBinding bind(NoiseChunk chunk, ProgramSet set, MarkerPairing pairing) {
        NoiseChunkAccessor acc = (NoiseChunkAccessor) chunk;
        // The chunk's final-density cache is add(finalDensity, BeardifierMarker) built by the NoiseChunk constructor,
        // outside the router, so the marker never appears in the router walk. Wire the marker to this chunk's beardifier.
        pairing.put(DensityFunctions.BeardifierMarker.INSTANCE, acc.ga2$beardifier());

        ColumnTemplate columnTemplate = DfcConfig.columnPrograms() ? set.column() : null;
        ColumnProgram column = null;
        int columnRoots = 0;
        if (columnTemplate != null) {
            column = columnTemplate.instantiate(resolve(pairing, columnTemplate.externTemplates));
            columnRoots = columnTemplate.rootCount();
        }
        // Even without a column program the cell programs need the interpolator order of the router's markers.
        List<Node.Marker> rootMarkers = set.column() != null ? set.column().rootMarkers : List.of();
        columnRoots = Math.max(columnRoots, rootMarkers.size());

        List<CellTemplate> cellTemplates = DfcConfig.cellPrograms() ? set.cells() : List.of();
        CellProgram[] cells = new CellProgram[cellTemplates.size()];
        int[] cellScratch = new int[cells.length];
        CellBounds[] bounds = new CellBounds[cells.length];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = cellTemplates.get(i).instantiate(resolve(pairing, cellTemplates.get(i).externTemplates));
            cellScratch[i] = cellTemplates.get(i).scratchSize();
            // the template's bounds object carries classification state. One per binding, as this binding is single-threaded
            bounds[i] = cellTemplates.get(i).bounds == null ? null : cellTemplates.get(i).bounds.fresh();
        }

        DensityFunction beardifier = acc.ga2$beardifier();
        // Cells may skip the beardifier only where it gives exactly 0: vanilla's marker everywhere, vanilla's compute away from
        // every piece. Another mod may change compute (YUNG's API adds its own pieces at the end of compute), and another kind
        // of beardifier is unknown. In those cases every cell asks the beardifier.
        BeardCuller culler = beardifier instanceof Beardifier b && b.getClass() == Beardifier.class && BeardCuller.mayCull(b) ? BeardCuller.of(b) : null;
        boolean beardEverywhere = culler == null && beardifier != DensityFunctions.BeardifierMarker.INSTANCE;

        ChunkBinding binding = new ChunkBinding(chunk, column, columnRoots, columnTemplate == null ? 0 : columnTemplate.scratchSize(), cells, cellScratch, bounds,
            culler, beardEverywhere);
        // Column values are position-pure only without old-chunk blending, without order-sensitive markers and without the chunk's
        // structures inside them. A neighbour may see other pieces at the shared border, and vanilla keeps each chunk's own values.
        if (columnTemplate != null && DfcConfig.borderColumnCache() && chunk.getBlender() == Blender.empty()
            && columnTemplate.statefulMarkers.isEmpty() && !columnTemplate.readsBeardifier && set.borderCache() != null) {
            binding.borderCache = set.borderCache();
        }
        for (int r = 0; r < rootMarkers.size(); r++) {
            DensityFunction wrapper = pairing.lookup(rootMarkers.get(r).vanilla());
            if (wrapper instanceof NoiseChunk.NoiseInterpolator interpolator) {
                binding.rootIndex.put(interpolator, r);
                binding.interpolatorByRoot[r] = interpolator;
            }
        }
        List<NoiseChunk.NoiseInterpolator> chunkInterpolators = acc.ga2$interpolators();
        boolean complete = binding.rootIndex.size() == chunkInterpolators.size() && binding.rootIndex.keySet().containsAll(chunkInterpolators);
        if (!complete) {
            if (DIAG_LOGS.getAndIncrement() < 3) {
                StringBuilder sb = new StringBuilder();
                sb.append("column binding incomplete: chunk has ").append(chunkInterpolators.size())
                    .append(" interpolators, ").append(binding.rootIndex.size()).append(" paired of ").append(rootMarkers.size())
                    .append(" markers; blender empty=").append(chunk.getBlender() == net.minecraft.world.level.levelgen.blending.Blender.empty())
                    .append("; walk divergences=").append(pairing.divergences());
                for (int r = 0; r < rootMarkers.size(); r++) {
                    DensityFunction w = pairing.lookup(rootMarkers.get(r).vanilla());
                    sb.append("\n  marker ").append(r).append(" -> ").append(w == null ? "null" : Names.mojangSimpleName(w.getClass()))
                        .append(w instanceof NoiseChunk.NoiseInterpolator ni && chunkInterpolators.contains(ni) ? " (in chunk list)" : " (NOT in chunk list)");
                }
                int idx = 0;
                for (NoiseChunk.NoiseInterpolator ni : chunkInterpolators) {
                    sb.append("\n  chunk interpolator ").append(idx++).append(" wrapped=").append(Names.mojangSimpleName(ni.wrapped().getClass()))
                        .append(binding.rootIndex.containsKey(ni) ? " paired" : " UNPAIRED");
                }
                dev.denismasterherobrine.generatoraccelerator2.dfc.pipeline.Compiler.LOGGER.warn(sb.toString());
            }
        }
        // Cell caches: datapack cache_all_in_cell markers resolve through the pairing. The chunk's own
        // final-density cache is the one wrapper no marker maps to.
        List<DensityFunction> cellCaches = acc.ga2$cellCaches();
        Map<DensityFunction, Boolean> claimed = new IdentityHashMap<>();
        for (int i = 0; i < cellTemplates.size(); i++) {
            Node.Marker m = cellTemplates.get(i).marker;
            if (m != null) {
                DensityFunction wrapper = pairing.lookup(m.vanilla());
                if (wrapper instanceof DensityFunctions.MarkerOrMarked mm) {
                    binding.cellIndex.put(mm.wrapped(), i);
                    claimed.put(wrapper, Boolean.TRUE);
                }
            }
        }
        for (int i = 0; i < cellTemplates.size(); i++) {
            if (cellTemplates.get(i).marker == null) {
                for (DensityFunction cache : cellCaches) {
                    if (!claimed.containsKey(cache) && cache instanceof DensityFunctions.MarkerOrMarked mm) {
                        binding.cellIndex.put(mm.wrapped(), i);
                        claimed.put(cache, Boolean.TRUE);
                        break;
                    }
                }
            }
        }
        if (!complete) {
            // the cell programs read every root's corners, and an unpaired root has none. So the cell caches fill the vanilla
            // way, from the chunk's own interpolators. The paired roots' columns stay compiled
            binding.cellIndex.clear();
        }
        return binding;
    }

    private static DensityFunction[] resolve(MarkerPairing pairing, DensityFunction[] templates) {
        DensityFunction[] externs = new DensityFunction[templates.length];
        for (int i = 0; i < externs.length; i++) {
            externs[i] = pairing.resolve(templates[i]);
        }
        return externs;
    }

    public boolean handles(NoiseChunk.NoiseInterpolator interpolator) {
        return column != null && rootIndex.containsKey(interpolator);
    }

    public boolean handlesCell(DensityFunction filler) {
        return cellIndex.containsKey(filler);
    }

    /** A column another chunk computed ({@link BorderColumnCache}): every root's corners, root after root ({@code cellCountY + 1} each). */
    private double[] currentShared;

    /** Replacement for {@code NoiseInterpolator.fillArray(slice, sliceFillingContextProvider)} during {@code fillSlice}. */
    public void fillSliceColumn(NoiseChunk.NoiseInterpolator interpolator, double[] target) {
        int x = acc.ga2$cellStartBlockX();
        int z = acc.ga2$cellStartBlockZ();
        int stride = cellCountY + 1;
        if (x != lastColumnX || z != lastColumnZ) {
            BorderColumnCache cache = BorderColumnCache.border(x, z) ? borderCache : null;
            long key = cache == null ? 0L : BorderColumnCache.key(Math.floorDiv(x, cellWidth), Math.floorDiv(z, cellWidth), cellNoiseMinY, cellCountY);
            double[] cached = cache == null ? null : cache.get(key);
            if (cached != null) {
                currentShared = cached;
                current = null;
            } else {
                column.fillColumn(chunk, columnScratch, out, x, z, cellNoiseMinY, cellHeight, cellCountY);
                current = out;
                currentShared = null;
                if (cache != null) {
                    // one array per column for the neighbours, with the key at its end. A row per root took one array per root
                    double[] snapshot = new double[out.length * stride + BorderColumnCache.KEY];
                    for (int r = 0; r < out.length; r++) {
                        System.arraycopy(out[r], 0, snapshot, r * stride, stride);
                    }
                    cache.put(key, snapshot);
                }
            }
            lastColumnX = x;
            lastColumnZ = z;
            columnsFilled++;
        }
        int root = rootIndex.get(interpolator);
        double[] shared = currentShared;
        if (shared != null) {
            System.arraycopy(shared, root * stride, target, 0, stride);
        } else {
            System.arraycopy(current[root], 0, target, 0, stride);
        }
        // Vanilla bumps interpolationCounter once per corner for every interpolator it fills.
        ((Ga2NoiseChunk) chunk).ga2$advanceInterpolationCounter(stride);
    }

    /** Replacement for {@code cache.noiseFiller.fillArray(cache.values, this)} during {@code selectCellYZ}. */
    public void fillCell(DensityFunction filler, double[] values) {
        int index = cellIndex.get(filler);
        CellProgram program = cells[index];
        for (int r = 0; r < interpolatorByRoot.length; r++) {
            NoiseInterpolatorAccessor it = (NoiseInterpolatorAccessor) interpolatorByRoot[r];
            if (it == null) {
                continue;
            }
            int b = r * 8;
            corners[b] = it.ga2$noise000();
            corners[b + 1] = it.ga2$noise100();
            corners[b + 2] = it.ga2$noise010();
            corners[b + 3] = it.ga2$noise110();
            corners[b + 4] = it.ga2$noise001();
            corners[b + 5] = it.ga2$noise101();
            corners[b + 6] = it.ga2$noise011();
            corners[b + 7] = it.ga2$noise111();
        }
        int x0 = acc.ga2$cellStartBlockX();
        int y0 = acc.ga2$cellStartBlockY();
        int z0 = acc.ga2$cellStartBlockZ();
        boolean beard = beardEverywhere || culler != null && culler.intersects(x0, y0, z0, x0 + cellWidth - 1, y0 + cellHeight - 1, z0 + cellWidth - 1);
        if (beard) {
            cellsWithBeard++;
        }
        cellsFilled++;
        if (values == classifyValues && !beard) {
            CellBounds b = bounds[index];
            int cls = b == null ? CellBounds.MIXED : b.classify(corners, y0, cellHeight, blenderEmpty);
            if (cls == CellBounds.SKY && !skipper.cellAir(x0, y0, z0, cellWidth, cellHeight)) {
                // the aquifer's pressure terms can still make blocks solid here, and they read the density
                cls = CellBounds.MIXED;
            }
            if (cls != CellBounds.MIXED) {
                if (cls == CellBounds.SKY) {
                    cellsSky++;
                } else {
                    cellsSolid++;
                }
                pendingProgram = program;
                pendingIndex = index;
                pendingValues = values;
                pendingX0 = x0;
                pendingY0 = y0;
                pendingZ0 = z0;
                ((Ga2NoiseChunk) chunk).ga2$setCellClass(cls);
                return;
            }
        }
        program.fillCell(chunk, cellScratch[index], values, corners, x0, y0, z0, cellWidth, cellHeight, beard ? 1 : 0);
        if (VERIFY_CELLS > 0 && VERIFY_SEEN.incrementAndGet() % VERIFY_CELLS == 0) {
            verifyCell(filler, values, x0, y0, z0);
        }
    }

    /**
     * {@code -Dgeneratoraccelerator2.verify.cells=N}: every N-th cell a program fills is filled again by vanilla's own fill
     * (the cell cache's density function over the NoiseChunk, as {@code selectCellYZ} does) and compared bit for bit. A
     * development aid. The second fill moves the memo of a {@code cache_once} on vanilla's array path. That matters only
     * where something reads the memo later in the same cell.
     */
    private static final int VERIFY_CELLS = Integer.getInteger("generatoraccelerator2.verify.cells", 0);
    private static final java.util.concurrent.atomic.AtomicLong VERIFY_SEEN = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong CELLS_VERIFIED = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong CELLS_DIFFERING = new java.util.concurrent.atomic.AtomicLong();

    private void verifyCell(DensityFunction filler, double[] values, int x0, int y0, int z0) {
        double[] expected = new double[values.length];
        filler.fillArray(expected, chunk);
        CELLS_VERIFIED.incrementAndGet();
        for (int i = 0; i < expected.length; i++) {
            if (Double.doubleToLongBits(expected[i]) != Double.doubleToLongBits(values[i])) {
                if (CELLS_DIFFERING.incrementAndGet() <= 10) {
                    Ga2Log.get("Verify").warn(
                        "cell at {},{},{} value {}: expected {}, got {}", x0, y0, z0, i, expected[i], values[i]);
                }
                return;
            }
        }
    }

    /**
     * Lets {@link #fillCell} classify the cells of the cell cache holding {@code values} (the final density) with
     * {@link CellBounds}. The values of an all-air or all-solid cell are left unfilled. The noise fill then computes
     * those cells' blocks without the values. Off again with {@link #stopClassifying}.
     */
    public void classifyCells(double[] values, CellSkipper skipper) {
        this.classifyValues = values;
        this.skipper = skipper;
    }

    public void stopClassifying() {
        this.classifyValues = null;
        this.skipper = null;
        this.pendingProgram = null;
    }

    /** Diagnostics: the corners of every root of the last cell and the bounds of its classification. */
    public String debugLastCell() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < interpolatorByRoot.length; r++) {
            if (interpolatorByRoot[r] == null) {
                continue;
            }
            sb.append(" root ").append(r).append(" [");
            for (int i = 0; i < 8; i++) {
                sb.append(i == 0 ? "" : ", ").append(corners[r * 8 + i]);
            }
            sb.append("]");
        }
        for (CellBounds b : bounds) {
            if (b != null) {
                sb.append(" bounds ").append(b.lastLo).append("..").append(b.lastHi);
            }
        }
        return sb.toString();
    }

    /** Fills the values of the cell {@link #fillCell} classified last, if they are still unfilled (the corners are still that cell's). */
    public void fillCurrentCell() {
        CellProgram program = pendingProgram;
        if (program != null) {
            pendingProgram = null;
            program.fillCell(chunk, cellScratch[pendingIndex], pendingValues, corners, pendingX0, pendingY0, pendingZ0, cellWidth, cellHeight, 0);
        }
    }
}
