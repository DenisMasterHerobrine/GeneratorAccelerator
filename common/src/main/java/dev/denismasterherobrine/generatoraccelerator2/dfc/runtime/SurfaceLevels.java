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
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;

/**
 * {@code NoiseChunk.computePreliminarySurfaceLevel} on the compiled surface level function. The walk is the same: from the
 * top of the noise down in steps of the cell height, to the first level whose value exceeds 0.390625. The function is
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

    /**
     * The compiled walk program, the program of the {@code flat_cache} children (null when there are none) and, by identity,
     * each such marker's base function (an extern of the walk program) with its root index in {@code flats}.
     */
    public record Templates(PointTemplate walk, @Nullable PointTemplate flats, Map<DensityFunction, Integer> flatIndex) {}

    private final PointProgram program;
    private final double[] scratch;
    private final double[] out;
    /** Set when only GA2's programs read the walk's positions (no extern but the flat values). Every step moves it. Else null. */
    @Nullable
    private final MutableContext moved;

    private SurfaceLevels(PointProgram program, boolean pure) {
        this.program = program;
        this.scratch = new double[Math.max(1, program.scratchSize)];
        this.out = new double[program.rootCount];
        this.moved = pure ? new MutableContext() : null;
    }

    /** The templates bound to a chunk. The walk's externs resolve to the chunk's wrappers ({@code wrapped} is its mapped router). */
    public static SurfaceLevels bind(Templates templates, NoiseRouter base, NoiseRouter wrapped) {
        return bind(templates, MarkerPairing.of(base, wrapped));
    }

    /** {@link #bind(Templates, NoiseRouter, NoiseRouter)} with the chunk's router pairing already made ({@link MarkerPairing#of}). */
    public static SurfaceLevels bind(Templates templates, MarkerPairing pairing) {
        FlatColumn flats = templates.flats() == null ? null : new FlatColumn(templates.flats().instantiate(templates.flats().externTemplates));
        PointTemplate walk = templates.walk();
        DensityFunction[] externs = new DensityFunction[walk.externTemplates.length];
        boolean pure = templates.flats() == null || templates.flats().externTemplates.length == 0;
        for (int i = 0; i < externs.length; i++) {
            Integer flat = templates.flatIndex().get(walk.externTemplates[i]);
            externs[i] = flat != null ? new FlatValue(flats, flat) : pairing.resolve(walk.externTemplates[i]);
            pure &= flat != null;
        }
        return new SurfaceLevels(walk.instantiate(externs), pure);
    }

    /** Vanilla's {@code computePreliminarySurfaceLevel} for the quart-aligned column {@code (x, z)}. */
    public int level(int x, int z, int minY, int height, int cellHeight) {
        COLUMNS.increment();
        boolean hoisted = false;
        MutableContext moved = this.moved;
        for (int y = minY + height; y >= minY; y -= cellHeight) {
            this.program.computeColumnPoint(moved != null ? moved.at(x, y, z) : new DensityFunction.SinglePointContext(x, y, z), this.scratch, this.out, hoisted);
            hoisted = true;
            if (this.out[0] > 0.390625) {
                return y;
            }
        }
        return Integer.MAX_VALUE;
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
