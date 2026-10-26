package dev.denismasterherobrine.generatoraccelerator2.dfc.runtime;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.aquifer.CellAir;
import dev.denismasterherobrine.generatoraccelerator2.dfc.DfcConfig;
import dev.denismasterherobrine.generatoraccelerator2.dfc.analysis.CellBounds;
import dev.denismasterherobrine.generatoraccelerator2.dfc.analysis.SkyBounds;
import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.CellProgram;
import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.CellTemplate;
import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.ColumnProgram;
import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.ColumnTemplate;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.Node;
import dev.denismasterherobrine.generatoraccelerator2.dfc.pipeline.Compiler;
import dev.denismasterherobrine.generatoraccelerator2.mixin.dfc.NoiseChunkAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.dfc.NoiseInterpolatorAccessor;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.blending.Blender;
import javax.annotation.Nullable;

/**
 * Per-{@link NoiseChunk} state of the compiled programs: the column program bound to this chunk's
 * cache wrappers, the cell programs bound to its cell caches, the interpolator-to-root mapping,
 * the beardifier culler and the scratch buffers. One chunk is only ever filled by one thread,
 * like vanilla's own interpolator state.
 */
public final class ChunkBinding {
    private static final AtomicInteger DIAG_LOGS = new AtomicInteger();

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

    /**
     * Sky corners ({@link SkyBounds}): the bounds over this chunk's corner columns, or null. With a plan ({@link #planSky}),
     * the first fill of a column computes its corners up to the highest one a cell that is not all air by the bounds touches.
     * The corners above are filled only if such a cell turns out to need them after all ({@link #completeCell}).
     */
    @Nullable private SkyBounds.Column sky;
    /** Corner columns per side while a plan is on, else 0. */
    private int skySide;
    private int skyMinX;
    private int skyMinZ;
    /** Per corner column ({@code x index * skySide + z index}): the highest corner index the first fill computes. */
    private int[] skyNeed;
    /** Per corner column: corners {@code [0, have)} are in this chunk's slices. */
    private int[] skyHave;
    /** Per cell column ({@code x index * cells + z index}): the lowest cell all air by the bounds, with all above. */
    private int[] skyFrom;
    /** The column program's output for part of a column. */
    private double[][] partOut;
    public static final LongAdder SKY_PLANS = new LongAdder();
    public static final LongAdder SKY_CORNERS_SKIPPED = new LongAdder();
    public static final LongAdder SKY_CORNERS_COMPLETED = new LongAdder();
    /** Tests: every N-th planned cell is filled block by block, with its corners filled later, as when the aquifer keeps fluid there. */
    public static int COMPLETE_EVERY;
    private int completeSeen;

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
                    .append(" markers; blender empty=").append(chunk.getBlender() == Blender.empty())
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
                Compiler.LOGGER.warn(sb.toString());
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
        // Sky corners need the final density to be the one cell program, classified without the beardifier everywhere, and
        // columns that are computed in parts with the same values (no blending: the set has no sky bounds otherwise)
        SkyBounds skyTemplate = set.sky();
        if (skyTemplate != null && SkyBounds.ENABLED && column != null && complete
            && binding.blenderEmpty && !beardEverywhere && cells.length == 1 && cellTemplates.get(0).marker == null && VERIFY_CELLS == 0) {
            binding.sky = skyTemplate.forChunk(resolve(pairing, skyTemplate.externTemplates()));
        }
        return binding;
    }

    /** Corner index {@code j}'s block y. */
    private int cornerY(int j) {
        return (j + cellNoiseMinY) * cellHeight;
    }

    /**
     * Plans the sky corners of the chunk at block {@code minX, minZ} before its first slice is filled. Called by the noise fill
     * while it classifies cells ({@link #classifyCells}), with the aquifer's {@code CellAir.skyPicker}. A cell counts as all air
     * from the plan when the bounds of the final density over it, from the bounds of the roots over its four columns, are at most
     * 0, no structure piece reaches it, and the picker gives air and no lava there. Those are the tests the fill's classification
     * makes, in the same terms, except the aquifer's own: the fill still asks it at the cell, in its order.
     */
    public void planSky(int minX, int minZ, @Nullable Aquifer.FluidPicker picker) {
        this.skySide = 0;
        if (sky == null || picker == null || classifyValues == null || cellWidth <= 0 || 16 % cellWidth != 0) {
            return;
        }
        int cells = 16 / cellWidth;
        int side = cells + 1;
        // the picker's fluids depend on y alone. A cell below its air line, or with its lava, is never all air
        int pickFrom = cellCountY;
        for (int cy = cellCountY - 1; cy >= 0; cy--) {
            int y0 = cornerY(cy);
            if (!CellAir.noLava(picker, minX, y0, minZ, cellWidth, cellHeight)
                || !CellAir.allAir(picker, minX, y0, minZ, cellWidth, cellHeight)) {
                break;
            }
            pickFrom = cy;
        }
        if (pickFrom >= cellCountY) {
            return;
        }
        int roots = interpolatorByRoot.length;
        int[] from = new int[side * side];
        double[][] lo = new double[side * side][roots];
        double[][] hi = new double[side * side][roots];
        int guess = pickFrom;
        for (int i = 0; i < side; i++) {
            for (int k = 0; k < side; k++) {
                int c = i * side + k;
                int x = minX + i * cellWidth;
                int z = minZ + k * cellWidth;
                sky.column(x, cornerY(0), z);
                from[c] = lowestSky(x, z, guess, pickFrom, lo[c], hi[c]);
                if (from[c] < cellCountY) {
                    guess = from[c];
                }
            }
        }
        int[] cellFrom = new int[cells * cells];
        double[] unionLo = new double[roots];
        double[] unionHi = new double[roots];
        int top = cornerY(cellCountY) - 1;
        boolean any = false;
        for (int a = 0; a < cells; a++) {
            for (int b = 0; b < cells; b++) {
                int c00 = a * side + b, c10 = (a + 1) * side + b, c01 = a * side + b + 1, c11 = (a + 1) * side + b + 1;
                int j0 = Math.max(Math.max(from[c00], from[c10]), Math.max(from[c01], from[c11]));
                int x0 = minX + a * cellWidth;
                int z0 = minZ + b * cellWidth;
                if (j0 < cellCountY) {
                    // each column's bounds hold from its own lowest sky corner up, so from j0 up too
                    for (int r = 0; r < roots; r++) {
                        unionLo[r] = Math.min(Math.min(lo[c00][r], lo[c10][r]), Math.min(lo[c01][r], lo[c11][r]));
                        unionHi[r] = Math.max(Math.max(hi[c00][r], hi[c10][r]), Math.max(hi[c01][r], hi[c11][r]));
                    }
                    if (!sky.sky(unionLo, unionHi, x0, cornerY(j0), z0, cellWidth, top)) {
                        j0 = cellCountY;
                    }
                }
                if (culler != null) {
                    for (int cy = cellCountY - 1; cy >= j0; cy--) {
                        int y0 = cornerY(cy);
                        if (culler.intersects(x0, y0, z0, x0 + cellWidth - 1, y0 + cellHeight - 1, z0 + cellWidth - 1)) {
                            j0 = cy + 1;
                            break;
                        }
                    }
                }
                cellFrom[a * cells + b] = j0;
                any |= j0 < cellCountY;
            }
        }
        if (!any) {
            return;
        }
        int[] need = new int[side * side];
        for (int i = 0; i < side; i++) {
            for (int k = 0; k < side; k++) {
                // a cell below its column's sky reads corners up to its own top: the lowest sky cell's bottom corner
                int n = 0;
                for (int a = Math.max(0, i - 1); a <= Math.min(cells - 1, i); a++) {
                    for (int b = Math.max(0, k - 1); b <= Math.min(cells - 1, k); b++) {
                        n = Math.max(n, cellFrom[a * cells + b]);
                    }
                }
                need[i * side + k] = Math.min(n, cellCountY);
            }
        }
        this.skyFrom = cellFrom;
        this.skyNeed = need;
        this.skyHave = new int[side * side];
        this.skyMinX = minX;
        this.skyMinZ = minZ;
        this.skySide = side;
        SKY_PLANS.increment();
    }

    /**
     * The lowest cell index from which the column's own bounds make every cell up to the top all air, at least {@code min}, or
     * {@link #cellCountY} for none. The roots' bounds at that cell go into {@code lo} and {@code hi}. Taller stretches have wider
     * bounds, so the answer is searched outward from {@code guess} (the neighbouring column's), then halved.
     */
    private int lowestSky(int x, int z, int guess, int min, double[] lo, double[] hi) {
        int top = cellCountY - 1;
        int g = Math.max(min, Math.min(top, guess));
        int low;
        int high;
        if (skyAt(x, z, g)) {
            high = g;
            low = min;
            int step = 1;
            while (high - step >= min) {
                int t = high - step;
                if (skyAt(x, z, t)) {
                    high = t;
                    step <<= 1;
                } else {
                    low = t + 1;
                    break;
                }
            }
        } else {
            low = g + 1;
            high = cellCountY;
            int step = 1;
            while (low + step - 1 <= top) {
                int t = low + step - 1;
                if (skyAt(x, z, t)) {
                    high = t;
                    break;
                }
                low = t + 1;
                step <<= 1;
            }
        }
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (skyAt(x, z, mid)) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }
        if (low < cellCountY) {
            sky.roots(cornerY(low), cornerY(cellCountY), lo, hi);
        }
        return low;
    }

    /** Whether the column's own bounds over its corners from cell {@code cy} up make every cell from {@code cy} up all air. */
    private boolean skyAt(int x, int z, int cy) {
        double[] lo = skyLo;
        double[] hi = skyHi;
        if (lo == null) {
            lo = skyLo = new double[interpolatorByRoot.length];
            hi = skyHi = new double[interpolatorByRoot.length];
        }
        sky.roots(cornerY(cy), cornerY(cellCountY), lo, hi);
        return sky.sky(lo, hi, x, cornerY(cy), z, cellWidth, cornerY(cellCountY) - 1);
    }

    private double[] skyLo;
    private double[] skyHi;

    /** Corners {@code [from, to]} of the column at block {@code x, z} into {@link #partOut}, root after root from index 0. */
    private void computePart(int x, int z, int from, int to) {
        double[][] part = partOut;
        if (part == null) {
            part = partOut = new double[out.length][cellCountY + 1];
        }
        // the program computes the corners at (index + cellNoiseMinY) * cellHeight, so a shifted minimum computes a later part
        column.fillColumn(chunk, columnScratch, part, x, z, cellNoiseMinY + from, cellHeight, to - from);
    }

    /** The highest corner index the plan computes for the column at block {@code x, z}, or {@link #cellCountY} for all. */
    private int plannedNeed(int x, int z) {
        if (skySide == 0) {
            return cellCountY;
        }
        int dx = x - skyMinX;
        int dz = z - skyMinZ;
        int i = dx / cellWidth;
        int k = dz / cellWidth;
        if (dx < 0 || dz < 0 || dx != i * cellWidth || dz != k * cellWidth || i >= skySide || k >= skySide) {
            return cellCountY;
        }
        return skyNeed[i * skySide + k];
    }

    private void setHave(int x, int z, int have) {
        if (skySide != 0) {
            int dx = x - skyMinX;
            int dz = z - skyMinZ;
            int i = dx / cellWidth;
            int k = dz / cellWidth;
            if (dx >= 0 && dz >= 0 && dx == i * cellWidth && dz == k * cellWidth && i < skySide && k < skySide) {
                skyHave[i * skySide + k] = have;
            }
        }
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
            int need = plannedNeed(x, z);
            // a snapshot holds every root's corners, how many of each it holds from the bottom, then its key
            int countAt = out.length * stride;
            BorderColumnCache cache = BorderColumnCache.border(x, z) ? borderCache : null;
            long key = cache == null ? 0L : BorderColumnCache.key(Math.floorDiv(x, cellWidth), Math.floorDiv(z, cellWidth), cellNoiseMinY, cellCountY);
            double[] cached = cache == null ? null : cache.get(key);
            if (cached != null && (int) cached[countAt] > need) {
                currentShared = cached;
                current = null;
                setHave(x, z, (int) cached[countAt]);
            } else {
                int have = 0;
                if (cached != null) {
                    // a neighbour computed the lower part of the column
                    have = (int) cached[countAt];
                    for (int r = 0; r < out.length; r++) {
                        System.arraycopy(cached, r * stride, out[r], 0, stride);
                    }
                }
                fillCorners(x, z, have, need);
                current = out;
                currentShared = null;
                setHave(x, z, need + 1);
                if (cache != null) {
                    // one array per column for the neighbours, with the key at its end. A row per root took one array per root
                    double[] snapshot = new double[countAt + 1 + BorderColumnCache.KEY];
                    for (int r = 0; r < out.length; r++) {
                        System.arraycopy(out[r], 0, snapshot, r * stride, stride);
                    }
                    snapshot[countAt] = need + 1;
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

    /** Corners {@code [from, to]} of the column at block {@code x, z} into {@link #out}, and no values above {@code to}. */
    private void fillCorners(int x, int z, int from, int to) {
        int stride = cellCountY + 1;
        if (from == 0) {
            column.fillColumn(chunk, columnScratch, out, x, z, cellNoiseMinY, cellHeight, to);
        } else if (from <= to) {
            computePart(x, z, from, to);
            for (int r = 0; r < out.length; r++) {
                System.arraycopy(partOut[r], 0, out[r], from, to - from + 1);
            }
        }
        if (to < cellCountY) {
            for (int r = 0; r < out.length; r++) {
                Arrays.fill(out[r], to + 1, stride, Double.NaN);
            }
            if (from == 0) {
                SKY_CORNERS_SKIPPED.add(cellCountY - to);
            }
        }
    }

    /** Whether the plan made the cell starting at block {@code x0, y0, z0} all air by the bounds. */
    private boolean plannedSky(int x0, int y0, int z0) {
        int cells = skySide - 1;
        int dx = x0 - skyMinX;
        int dz = z0 - skyMinZ;
        int a = dx / cellWidth;
        int b = dz / cellWidth;
        if (dx < 0 || dz < 0 || a >= cells || b >= cells) {
            return false;
        }
        return Math.floorDiv(y0, cellHeight) - cellNoiseMinY >= skyFrom[a * cells + b];
    }

    /**
     * Fills the corners a planned cell needs after all, because the aquifer keeps fluid in it: the missing corners of its four
     * columns up to its top, into the interpolators' slices (the current x step's two). Corners are values of their position
     * alone, so computed now they are the values the first fill would have computed. Then the cell's corners are loaded again,
     * as {@code selectCellYZ} loads them.
     */
    private void completeCell(int x0, int y0, int z0) {
        int a = (x0 - skyMinX) / cellWidth;
        int b = (z0 - skyMinZ) / cellWidth;
        int cy = Math.floorDiv(y0, cellHeight) - cellNoiseMinY;
        int to = cy + 1;
        for (int di = 0; di <= 1; di++) {
            for (int dk = 0; dk <= 1; dk++) {
                int c = (a + di) * skySide + b + dk;
                int have = skyHave[c];
                if (have > to) {
                    continue;
                }
                computePart(skyMinX + (a + di) * cellWidth, skyMinZ + (b + dk) * cellWidth, have, to);
                for (int r = 0; r < interpolatorByRoot.length; r++) {
                    NoiseChunk.NoiseInterpolator it = interpolatorByRoot[r];
                    if (it != null) {
                        NoiseInterpolatorAccessor ia = (NoiseInterpolatorAccessor) it;
                        double[] row = (di == 0 ? ia.ga2$slice0() : ia.ga2$slice1())[b + dk];
                        System.arraycopy(partOut[r], 0, row, have, to - have + 1);
                    }
                }
                skyHave[c] = to + 1;
                SKY_CORNERS_COMPLETED.add(to - have + 1);
            }
        }
        for (NoiseChunk.NoiseInterpolator it : interpolatorByRoot) {
            if (it != null) {
                ((NoiseInterpolatorAccessor) it).ga2$selectCellYZ(cy, b);
            }
        }
        loadCorners();
    }

    /** The current cell's corners of every root, from the interpolators, into {@link #corners}. */
    private void loadCorners() {
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
    }

    /** Replacement for {@code cache.noiseFiller.fillArray(cache.values, this)} during {@code selectCellYZ}. */
    public void fillCell(DensityFunction filler, double[] values) {
        int index = cellIndex.get(filler);
        CellProgram program = cells[index];
        loadCorners();
        int x0 = acc.ga2$cellStartBlockX();
        int y0 = acc.ga2$cellStartBlockY();
        int z0 = acc.ga2$cellStartBlockZ();
        boolean beard = beardEverywhere || culler != null && culler.intersects(x0, y0, z0, x0 + cellWidth - 1, y0 + cellHeight - 1, z0 + cellWidth - 1);
        if (beard) {
            cellsWithBeard++;
        }
        cellsFilled++;
        if (values == classifyValues && !beard) {
            // a planned cell's corners may not be computed. Its bounds from the roots' bounds already say all air, and the
            // corners lie within those bounds, so the classification from the corners would say all air too
            boolean planned = skySide != 0 && plannedSky(x0, y0, z0);
            CellBounds b = bounds[index];
            int cls = planned ? CellBounds.SKY : b == null ? CellBounds.MIXED : b.classify(corners, x0, y0, z0, cellWidth, cellHeight, blenderEmpty);
            if (cls == CellBounds.SKY && (!skipper.cellAir(x0, y0, z0, cellWidth, cellHeight) || planned && COMPLETE_EVERY > 0 && ++completeSeen % COMPLETE_EVERY == 0)) {
                // the aquifer's pressure terms can still make blocks solid here, and they read the density
                cls = CellBounds.MIXED;
                if (planned) {
                    completeCell(x0, y0, z0);
                }
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
    private static final AtomicLong VERIFY_SEEN = new AtomicLong();
    public static final AtomicLong CELLS_VERIFIED = new AtomicLong();
    public static final AtomicLong CELLS_DIFFERING = new AtomicLong();

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
        // any later slice of this NoiseChunk is filled whole
        this.skySide = 0;
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
