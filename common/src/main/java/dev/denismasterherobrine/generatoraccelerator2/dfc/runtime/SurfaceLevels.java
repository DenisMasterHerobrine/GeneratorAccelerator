package dev.denismasterherobrine.generatoraccelerator2.dfc.runtime;

import dev.denismasterherobrine.generatoraccelerator2.dfc.analysis.CoordDeps;
import dev.denismasterherobrine.generatoraccelerator2.dfc.analysis.RefCount;
import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.PointProgram;
import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.PointTemplate;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.Node;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseRouter;

/**
 * {@code NoiseChunk.computePreliminarySurfaceLevel} on the compiled surface level function. A router's surface level is usually a
 * {@code find_top_surface}: from its upper bound, rounded down to the cell height, down in steps of the cell height to the first
 * level whose density exceeds 0, else its lower bound. The walk is the same here, on the compiled density and upper bound
 * ({@link Top}). Any other surface level function is compiled whole and floored at y 0, as vanilla floors it. The functions are
 * evaluated by a program bound to the chunk ({@link Templates#walk}, the chunk marker policy). The program computes the
 * function's x/z-only parts once per column instead of at every step. {@code cache_2d} wrappers over functions that read y
 * stay the chunk's objects, so their remembered values are vanilla's. Functions the compiler does not lower are called as
 * the chunk's own objects. Every step gets a fresh {@code SinglePointContext}, as vanilla's does, unless only GA2's
 * programs read it ({@link MutableContext}).
 *
 * <p>The walk asks columns up to three chunks around the chunk. There, the chunk's {@code flat_cache} wrappers evaluate
 * their child (vanilla's interpreted tree) instead of reading their table. Every {@code flat_cache} of the function must
 * wrap a function of x and z only. A wrapper's value at a quart-aligned column is then the child's value there, both
 * inside the chunk (the table holds the child at the quart corner at y 0) and outside it. So the walk program calls a
 * compiled program of those children once per column, in place of the wrappers ({@link Templates#flats}). That program
 * treats markers as transparent, because they are pure.
 *
 * <p>One instance per NoiseChunk (single-threaded, as the chunk is). {@code -Dgeneratoraccelerator2.surfaceLevelProgram=false}
 * turns it off.
 */
public final class SurfaceLevels {
    public static final boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.surfaceLevelProgram"));
    /** Columns computed by the program (always counted: one increment per column). */
    public static final LongAdder COLUMNS = new LongAdder();

    /** The router roots of a {@code find_top_surface}'s density and upper bound, and of any other surface level function. */
    public static final String DENSITY = "preliminary_surface_level.density";
    public static final String UPPER_BOUND = "preliminary_surface_level.upper_bound";
    public static final String WHOLE = "preliminary_surface_level";

    /** A router's {@code find_top_surface}: the function the walk evaluates and its bounds. */
    public record Top(DensityFunction density, DensityFunction upperBound, int lowerBound, int cellHeight) {
        /** The surface level function as a {@code find_top_surface} behind its holders, or null. */
        @Nullable
        public static Top of(DensityFunction level) {
            while (level instanceof DensityFunctions.HolderHolder h) {
                level = h.function().value();
            }
            return level instanceof DensityFunctions.FindTopSurface f ? new Top(f.density(), f.upperBound(), f.lowerBound(), f.cellHeight()) : null;
        }
    }

    /** The router roots the surface level evaluates, as {@code roots} holds them. */
    public static String[] fields(Map<String, Node> roots) {
        return roots.containsKey(DENSITY) ? new String[] { DENSITY, UPPER_BOUND } : new String[] { WHOLE };
    }

    /**
     * The compiled walk program, the compiled upper bound (with {@code top} only), the program of the {@code flat_cache} children
     * (null when there are none) and, by identity, each such marker's base function (an extern of the walk or upper bound program)
     * with its root index in {@code flats}. With {@code top}, the walk's root is the density. Without it, it is the whole function.
     */
    public record Templates(PointTemplate walk, @Nullable PointTemplate upper, @Nullable PointTemplate flats, Map<DensityFunction, Integer> flatIndex,
                            @Nullable Top top) {}

    private final PointProgram program;
    @Nullable
    private final PointProgram upper;
    @Nullable
    private final Top top;
    private final double[] scratch;
    private final double[] upperScratch;
    private final double[] out;
    /** Set when only GA2's programs read the walk's positions (no extern but the flat values). Every step moves it. Else null. */
    @Nullable
    private final MutableContext moved;

    private SurfaceLevels(PointProgram program, @Nullable PointProgram upper, @Nullable Top top, boolean pure) {
        this.program = program;
        this.upper = upper;
        this.top = top;
        this.scratch = new double[Math.max(1, program.scratchSize)];
        this.upperScratch = upper == null ? null : new double[Math.max(1, upper.scratchSize)];
        this.out = new double[Math.max(program.rootCount, upper == null ? 0 : upper.rootCount)];
        this.moved = pure ? new MutableContext() : null;
    }

    /** The templates bound to a chunk. The walk's externs resolve to the chunk's wrappers ({@code wrapped} is its mapped router). */
    public static SurfaceLevels bind(Templates templates, NoiseRouter base, NoiseRouter wrapped) {
        return bind(templates, MarkerPairing.of(base, wrapped));
    }

    /** {@link #bind(Templates, NoiseRouter, NoiseRouter)} with the chunk's router pairing already made ({@link MarkerPairing#of}). */
    public static SurfaceLevels bind(Templates templates, MarkerPairing pairing) {
        FlatColumn flats = templates.flats() == null ? null : new FlatColumn(templates.flats().instantiate(templates.flats().externTemplates));
        boolean[] pure = { templates.flats() == null || templates.flats().externTemplates.length == 0 };
        PointProgram walk = instantiate(templates.walk(), templates, flats, pairing, pure);
        PointProgram upper = templates.upper() == null ? null : instantiate(templates.upper(), templates, flats, pairing, pure);
        return new SurfaceLevels(walk, upper, templates.top(), pure[0]);
    }

    private static PointProgram instantiate(PointTemplate template, Templates templates, @Nullable FlatColumn flats, MarkerPairing pairing, boolean[] pure) {
        DensityFunction[] externs = new DensityFunction[template.externTemplates.length];
        for (int i = 0; i < externs.length; i++) {
            Integer flat = templates.flatIndex().get(template.externTemplates[i]);
            externs[i] = flat != null ? new FlatValue(flats, flat) : pairing.resolve(template.externTemplates[i]);
            pure[0] &= flat != null;
        }
        return template.instantiate(externs);
    }

    /** Vanilla's {@code computePreliminarySurfaceLevel} for the quart-aligned column {@code (x, z)}. */
    public int level(int x, int z) {
        COLUMNS.increment();
        MutableContext moved = this.moved;
        Top top = this.top;
        if (top == null) {
            // vanilla floors the function at y 0
            this.program.computeColumnPoint(moved != null ? moved.at(x, 0, z) : new DensityFunction.SinglePointContext(x, 0, z), this.scratch, this.out, false);
            return Mth.floor(this.out[0]);
        }
        // FindTopSurface.compute at y 0: the upper bound there, then the density from the rounded bound down
        this.upper.computeColumnPoint(moved != null ? moved.at(x, 0, z) : new DensityFunction.SinglePointContext(x, 0, z), this.upperScratch, this.out, false);
        int cellHeight = top.cellHeight();
        int lowerBound = top.lowerBound();
        int topY = Mth.floor(this.out[0] / cellHeight) * cellHeight;
        if (topY <= lowerBound) {
            return lowerBound;
        }
        boolean hoisted = false;
        for (int y = topY; y >= lowerBound; y -= cellHeight) {
            this.program.computeColumnPoint(moved != null ? moved.at(x, y, z) : new DensityFunction.SinglePointContext(x, y, z), this.scratch, this.out, hoisted);
            hoisted = true;
            if (this.out[0] > 0.0) {
                return y;
            }
        }
        return lowerBound;
    }

    /**
     * The distinct {@code flat_cache} markers under {@code root} in the order first met, or null when one of them wraps a
     * function that reads y.
     */
    @Nullable
    public static List<Node.Marker> flatCaches(Node root) {
        CoordDeps deps = new CoordDeps(false);
        Map<Node, Boolean> seen = new IdentityHashMap<>();
        ArrayDeque<Node> stack = new ArrayDeque<>();
        List<Node.Marker> out = new ArrayList<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Node n = stack.pop();
            if (seen.put(n, Boolean.TRUE) != null) {
                continue;
            }
            if (n instanceof Node.Marker m && m.type() == Node.MarkerType.FLAT_CACHE) {
                if (!deps.of(m.child()).isXzOrConst()) {
                    return null;
                }
                out.add(m);
                // what is under it is evaluated by the flats program, not by the walk
                continue;
            }
            for (Node c : RefCount.children(n)) {
                stack.push(c);
            }
        }
        return out;
    }

    /** The {@code flat_cache} children at one column, computed together when the walk asks for a new column. */
    private static final class FlatColumn {
        private final PointProgram program;
        private final double[] scratch;
        private final double[] values;
        private boolean valid;
        private int x;
        private int z;

        FlatColumn(PointProgram program) {
            this.program = program;
            this.scratch = new double[Math.max(1, program.scratchSize)];
            this.values = new double[program.rootCount];
        }

        double value(DensityFunction.FunctionContext ctx, int index) {
            int bx = ctx.blockX();
            int bz = ctx.blockZ();
            if (!this.valid || bx != this.x || bz != this.z) {
                this.program.computeAll(ctx, this.scratch, this.values);
                this.valid = true;
                this.x = bx;
                this.z = bz;
            }
            return this.values[index];
        }
    }

    /** One {@code flat_cache} of the walk, as the compiled child at the asked column. */
    private record FlatValue(FlatColumn column, int index) implements DensityFunction {
        @Override
        public double compute(FunctionContext context) {
            return this.column.value(context, this.index);
        }

        @Override
        public void fillArray(double[] array, ContextProvider provider) {
            provider.fillAllDirectly(array, this);
        }

        @Override
        public DensityFunction mapAll(Visitor visitor) {
            return visitor.apply(this);
        }

        @Override
        public double minValue() {
            return Double.NEGATIVE_INFINITY;
        }

        @Override
        public double maxValue() {
            return Double.POSITIVE_INFINITY;
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException("a compiled surface level value has no codec");
        }
    }
}
