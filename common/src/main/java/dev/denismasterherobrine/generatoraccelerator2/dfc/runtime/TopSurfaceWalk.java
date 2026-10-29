package dev.denismasterherobrine.generatoraccelerator2.dfc.runtime;

import dev.denismasterherobrine.generatoraccelerator2.api.DensityFunctionLowering;
import dev.denismasterherobrine.generatoraccelerator2.dfc.analysis.CoordDeps;
import dev.denismasterherobrine.generatoraccelerator2.dfc.analysis.RefCount;
import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.DfcCompileException;
import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.Emitter;
import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.PointProgram;
import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.PointTemplate;
import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.ProgramCompiler;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.INode;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.IrBuilder;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.Node;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

/**
 * A {@code find_top_surface} inside another function, compiled. The walk stays a loop: from the upper bound, rounded down to the
 * cell height, down in steps of the cell height to the first level whose density exceeds 0, else the lower bound
 * ({@code DensityFunctions.FindTopSurface.compute}). The upper bound compiles with the code around it. The density compiles into a
 * program of its own, which the walk runs once per step. The code around calls the walk as a sampler of (x, upper bound, z)
 * ({@link ExternSamplers}). A router's own surface level is walked by {@link SurfaceLevels} instead.
 *
 * <p>Vanilla walks the chunk's copy of the density, with a new {@code SinglePointContext} per step. The chunk's wrappers then
 * compute their function at the step's position, so the copy gives the density's own values. That does not hold for a
 * {@code flat_cache} (the chunk's table answers for its own grid), a {@code cache_2d} over a function of y (its last value depends
 * on order), blending and the beardifier (they read the chunk), or a function the compiler keeps as its own code. A density with
 * any of them is left to vanilla.
 */
public final class TopSurfaceWalk implements ExternSamplers.Sampler {
    /** The lowering, which {@code IrBuilder} applies to every {@code find_top_surface} it meets. */
    public static final DensityFunctionLowering LOWERING = (fn, ctx) -> lower((DensityFunctions.FindTopSurface) fn, (IrBuilder) ctx);

    private final PointProgram density;
    private final int lowerBound;
    private final int cellHeight;
    /** True when the density program calls no extern function. Each step then moves one context ({@link MutableContext}). */
    private final boolean pure;
    /** Programs keep every intermediate value in the scratch, so each thread has its own. */
    private final ThreadLocal<Mine> perThread;

    private static final class Mine {
        final double[] scratch;
        final double[] out;
        final MutableContext ctx = new MutableContext();

        Mine(PointProgram program) {
            this.scratch = new double[Math.max(1, program.scratchSize)];
            this.out = new double[program.rootCount];
        }
    }

    private TopSurfaceWalk(PointProgram density, int lowerBound, int cellHeight, boolean pure) {
        this.density = density;
        this.lowerBound = lowerBound;
        this.cellHeight = cellHeight;
        this.pure = pure;
        this.perThread = ThreadLocal.withInitial(() -> new Mine(density));
    }

    /** {@code FindTopSurface.compute} at block column {@code (x, z)}, with the upper bound already computed there. */
    @Override
    public double sample(double x, double upper, double z) {
        int topY = Mth.floor(upper / this.cellHeight) * this.cellHeight;
        if (topY <= this.lowerBound) {
            return this.lowerBound;
        }
        Mine mine = this.perThread.get();
        int blockX = (int) x;
        int blockZ = (int) z;
        for (int blockY = topY; blockY >= this.lowerBound; blockY -= this.cellHeight) {
            DensityFunction.FunctionContext at = this.pure ? mine.ctx.at(blockX, blockY, blockZ) : new DensityFunction.SinglePointContext(blockX, blockY, blockZ);
            this.density.computeAll(at, mine.scratch, mine.out);
            if (mine.out[0] > 0.0) {
                return blockY;
            }
        }
        return this.lowerBound;
    }

    @Nullable
    private static Node lower(DensityFunctions.FindTopSurface fn, IrBuilder ctx) {
        Node upper = ctx.lower(fn.upperBound());
        Node density = ctx.lower(fn.density());
        String why = chunkDependent(density, new CoordDeps(false), new IdentityHashMap<>());
        if (why != null) {
            ctx.decline("density has " + why);
            return null;
        }
        PointTemplate template;
        try {
            template = ProgramCompiler.compilePointTemplate("top-surface", List.of(density), ctx.specs(), ctx.blendedSpecs(), Emitter.Mode.POINT);
        } catch (DfcCompileException e) {
            ctx.decline("density not compiled: " + e.getMessage());
            return null;
        }
        TopSurfaceWalk walk = new TopSurfaceWalk(template.instantiate(template.externTemplates), fn.lowerBound(), fn.cellHeight(),
            template.externTemplates.length == 0);
        int index = ExternSamplers.register(fn, walk);
        Node x = ctx.intern(new Node.IntToDouble(new INode.IntCoord(0)));
        Node z = ctx.intern(new Node.IntToDouble(new INode.IntCoord(2)));
        Node call = ctx.intern(new Node.StaticCall(ExternSamplers.OWNER, ExternSamplers.NAME, ExternSamplers.DESC, List.of(new INode.IntConst(index), x, upper, z)));
        // the function's own bounds, which a parent's min and max skips read. x * 1.0 is x for every double
        return ctx.intern(new Node.MulOrAdd(true, call, 1.0, fn.minValue(), fn.maxValue()));
    }

    /** What makes the chunk's copy of {@code n} differ from {@code n} at another position, or null when nothing does. */
    @Nullable
    private static String chunkDependent(Node n, CoordDeps deps, Map<Node, Boolean> seen) {
        if (seen.put(n, Boolean.TRUE) != null) {
            return null;
        }
        String own = switch (n) {
            case Node.Marker m when m.type() == Node.MarkerType.FLAT_CACHE -> "a flat_cache (the chunk's table answers for its own grid)";
            case Node.Marker m when m.type() == Node.MarkerType.CACHE_2D && !deps.of(m.child()).isXzOrConst() ->
                "a cache_2d over a function of y (its last value depends on order)";
            case Node.BlendDensity b -> "blend_density (reads the chunk's blending)";
            case Node.BlendAlpha a -> "blend_alpha (reads the chunk's blending)";
            case Node.BlendOffset o -> "blend_offset (reads the chunk's blending)";
            case Node.Beardifier b -> "the beardifier (reads the chunk's structures)";
            case Node.Unsupported u -> "an uncompiled " + u.vanilla().getClass().getName();
            default -> null;
        };
        if (own != null) {
            return own;
        }
        for (Node child : RefCount.children(n)) {
            String why = chunkDependent(child, deps, seen);
            if (why != null) {
                return why;
            }
        }
        return null;
    }
}
