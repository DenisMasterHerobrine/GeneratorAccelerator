package dev.denismasterherobrine.generatoraccelerator2.dfc.runtime;

import com.google.common.collect.MapMaker;
import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.PointProgram;
import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.PointTemplate;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;

/**
 * A chunk's {@code Climate.Sampler} (created by {@code NoiseChunk.cachedClimateSampler}) bound to
 * the compiled six-root point program, so one biome sample evaluates every shared subexpression once.
 * A {@code RandomState}'s own sampler is bound too ({@link #bindShared}).
 * Samplers are records and cannot carry fields, so bindings live in a weak identity map.
 *
 * <p>The map holds keys and values weakly. A binding is held by its owner: the NoiseChunk that made the sampler, or the
 * RandomState for a shared binding. A binding holds the chunk's wrapped functions, and so the NoiseChunk. A strong value
 * kept its key alive whenever the NoiseChunk held its own sampler (YUNG's Cave Biomes does). Then no entry ever went, and
 * every NoiseChunk stayed in memory.
 */
public final class BoundSampler {
    private static final ConcurrentMap<Climate.Sampler, BoundSampler> REGISTRY = new MapMaker().weakKeys().weakValues().concurrencyLevel(8).makeMap();

    private final PointProgram program;
    private final double[] scratch;
    private final double[] out;
    public long samples;
    /**
     * One scratch per quart column of the chunk (4 x 4). A biome fill samples every column at 96 heights. A column's
     * (x, z)-only values are computed once. In the overworld these are all climate values except the depth gradient.
     */
    private final double[][] columnScratch = new double[16][];
    private final int[] columnX = new int[16];
    private final int[] columnZ = new int[16];
    public static boolean COLUMNS = !"false".equals(System.getProperty("generatoraccelerator2.samplerColumns"));
    /** {@code -Dgeneratoraccelerator2.globalSampler=false} leaves a RandomState's own sampler on vanilla's functions. */
    public static boolean GLOBAL = !"false".equals(System.getProperty("generatoraccelerator2.globalSampler"));

    /**
     * Shared bindings only. A RandomState's own sampler is called from any thread: the stronghold ring search on the worker
     * pool, structure biome checks in parallel structure starts, the spawn search. So every thread keeps its own scratch
     * and output. The POINT program keeps every intermediate value in the scratch.
     */
    @Nullable
    private final ThreadLocal<Mine> perThread;
    @Nullable
    public final LongAdder sharedSamples;
    /** True when the program calls no extern function. A sample's position is then a moved context ({@link MutableContext}). */
    private final boolean pure;
    /** This binding's moved context, for a chunk's binding. A shared binding keeps one per thread. */
    private final MutableContext moved = new MutableContext();

    /** A thread's scratch, output and context for a shared binding. */
    private static final class Mine {
        final double[] scratch;
        final double[] out;
        final MutableContext ctx = new MutableContext();

        Mine(PointProgram program) {
            this.scratch = new double[Math.max(1, program.scratchSize)];
            this.out = new double[program.rootCount];
        }
    }

    private BoundSampler(PointProgram program, boolean shared, boolean pure) {
        this.program = program;
        this.scratch = new double[Math.max(1, program.scratchSize)];
        this.out = new double[program.rootCount];
        this.perThread = shared ? ThreadLocal.withInitial(() -> new Mine(program)) : null;
        this.sharedSamples = shared ? new LongAdder() : null;
        this.pure = pure;
    }

    public static BoundSampler lookup(Climate.Sampler sampler) {
        return REGISTRY.get(sampler);
    }

    /**
     * Drops a chunk sampler's binding once its biome fill is done ({@code mixin.biome.BiomeFillMixin}). The sampler belongs to the
     * fill ({@code NoiseChunk.cachedClimateSampler} makes one per call). A weak map lets go of a dead entry only on a later write
     * to its segment, so the entry goes here at once. A shared binding (a RandomState's own sampler, which other generators fill
     * with) stays. Samplers made by other callers are never released. Their entries go with the NoiseChunk that owns them.
     */
    public static void release(Climate.Sampler sampler) {
        BoundSampler bound = REGISTRY.get(sampler);
        if (bound != null && bound.perThread == null) {
            REGISTRY.remove(sampler, bound);
        }
    }

    /**
     * Bound samplers by temperature function, with their six functions. Another mod's sampler made of per-point wrappers
     * around the same six functions (Blueprint's) samples the same values and can use the same binding ({@link #alias}).
     * Weak values: a binding holds its temperature function, which is its key. With strong values no entry ever went, and
     * every chunk's NoiseChunk stayed reachable through its wrapped functions. A binding lives while its owner does, that is,
     * while its sampler is in use. That is also when another mod's sampler around its functions is asked.
     */
    private static final ConcurrentMap<DensityFunction, BoundSampler> BY_TEMPERATURE = new MapMaker().weakKeys().weakValues().concurrencyLevel(8).makeMap();
    private DensityFunction[] functions;

    /** The binding whose six climate functions are exactly these (by identity), or null. */
    @Nullable
    public static BoundSampler forFunctions(DensityFunction temperature, DensityFunction humidity, DensityFunction continentalness, DensityFunction erosion,
                                            DensityFunction depth, DensityFunction weirdness) {
        BoundSampler b = BY_TEMPERATURE.get(temperature);
        if (b == null) {
            return null;
        }
        DensityFunction[] f = b.functions;
        return f[1] == humidity && f[2] == continentalness && f[3] == erosion && f[4] == depth && f[5] == weirdness ? b : null;
    }

    /**
     * Serves {@code sampler} with {@code bound} (a sampler that samples the same values; see {@link #forFunctions}). The entry
     * lives while {@code bound}'s owner does.
     */
    public static void alias(Climate.Sampler sampler, BoundSampler bound) {
        REGISTRY.put(sampler, bound);
    }

    /** Registers the binding. The caller keeps it for as long as the sampler is used: the map holds it weakly. */
    private static BoundSampler register(Climate.Sampler sampler, BoundSampler bound) {
        bound.functions = new DensityFunction[] { sampler.temperature(), sampler.humidity(), sampler.continentalness(), sampler.erosion(), sampler.depth(),
            sampler.weirdness() };
        REGISTRY.put(sampler, bound);
        BY_TEMPERATURE.put(sampler.temperature(), bound);
        return bound;
    }

    /**
     * Binds {@code sampler} (whose fields are the chunk-wrapped router fields) to the template. The NoiseChunk that made the
     * sampler keeps the returned binding.
     */
    public static BoundSampler bind(Climate.Sampler sampler, PointTemplate template, NoiseRouter baseRouter) {
        MarkerPairing pairing = MarkerPairing.ofSampler(baseRouter, sampler);
        DensityFunction[] externs = new DensityFunction[template.externTemplates.length];
        for (int i = 0; i < externs.length; i++) {
            externs[i] = pairing.resolve(template.externTemplates[i]);
        }
        return register(sampler, new BoundSampler(template.instantiate(externs), false, externs.length == 0));
    }

    /**
     * Binds a RandomState's own sampler to the template, for any thread. The sampler's fields are the router's climate fields
     * with every marker unwrapped and every holder resolved. These are the trees the template was compiled from in POINT mode.
     * The RandomState keeps the returned binding.
     */
    public static BoundSampler bindShared(Climate.Sampler sampler, PointTemplate template) {
        return register(sampler, new BoundSampler(template.instantiate(template.externTemplates), true, template.externTemplates.length == 0));
    }

    /** Same contract as {@code Climate.Sampler.sample}: quart coordinates in, target point out. */
    public Climate.TargetPoint sample(int quartX, int quartY, int quartZ) {
        double[] out = this.compute(quartX, quartY, quartZ);
        return Climate.target((float) out[0], (float) out[1], (float) out[2], (float) out[3], (float) out[4], (float) out[5]);
    }

    /**
     * The six climate values that {@link #sample} builds its target point from: temperature, humidity, continentalness, erosion,
     * depth, weirdness. They are doubles here. {@code Climate.target} takes them as floats. The array belongs to this binding (to
     * this thread, for a shared binding), and the next sample overwrites it.
     */
    public double[] compute(int quartX, int quartY, int quartZ) {
        int blockX = QuartPos.toBlock(quartX);
        int blockY = QuartPos.toBlock(quartY);
        int blockZ = QuartPos.toBlock(quartZ);
        if (this.perThread != null) {
            Mine mine = this.perThread.get();
            double[] out = mine.out;
            program.computeAll(this.pure ? mine.ctx.at(blockX, blockY, blockZ) : new DensityFunction.SinglePointContext(blockX, blockY, blockZ), mine.scratch, out);
            this.sharedSamples.increment();
            return out;
        }
        DensityFunction.FunctionContext ctx = this.pure ? this.moved.at(blockX, blockY, blockZ) : new DensityFunction.SinglePointContext(blockX, blockY, blockZ);
        if (COLUMNS) {
            int slot = (quartX & 3) | (quartZ & 3) << 2;
            double[] columnScratch = this.columnScratch[slot];
            boolean same = columnScratch != null && this.columnX[slot] == blockX && this.columnZ[slot] == blockZ;
            if (columnScratch == null) {
                columnScratch = new double[this.scratch.length];
                this.columnScratch[slot] = columnScratch;
            }
            this.columnX[slot] = blockX;
            this.columnZ[slot] = blockZ;
            program.computeColumnPoint(ctx, columnScratch, out, same);
        } else {
            program.computeAll(ctx, scratch, out);
        }
        samples++;
        return out;
    }
}
