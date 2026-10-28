package dev.denismasterherobrine.generatoraccelerator2.surface.compile;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import javax.annotation.Nullable;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * How {@code compat/<modid>} teaches the surface rule compiler other mods' rule and condition types. A lowering turns
 * such a source into {@link Ir}: into vanilla nodes where the type is put together from vanilla ones, or into an
 * extern node. An extern node is code that evaluates the type directly on the evaluation state ({@link SurfaceEval},
 * with the block position, the surface system and the column's values). Unlike an opaque node, an extern needs no
 * vanilla rule context mirrored per block, so the builder keeps its fast paths. Lowerings are tried in registration
 * order before a source becomes opaque.
 */
public final class SurfaceExtern {
    private SurfaceExtern() {}

    /** A rule evaluated by compat code: {@code SurfaceRule.tryApply} at the evaluation state's current block. */
    public interface Rule {
        BlockState apply(SurfaceEval s);

        /**
         * True when the result depends only on the block's position and the chunk's seed-dependent state (not on the
         * column's counters or blocks). The deep-band plan may then leave the rule for per-block evaluation like
         * vanilla's bands, instead of stopping there.
         */
        default boolean positional() {
            return false;
        }
    }

    /** A condition evaluated by compat code. Its value is cached for the scope it declares, like vanilla's lazy conditions. */
    public interface Cond {
        boolean test(SurfaceEval s);

        Ir.Scope scope();

        /** True when, like {@link Rule#positional()}, it depends only on the position and the chunk's seed-dependent state. */
        default boolean positional() {
            return false;
        }
    }

    /**
     * An extern rule or condition the GPU's surface can run ({@code gpu.surface.SurfaceSpec}). Its OpenCL reads the evaluation state
     * {@code s} (the block's {@code s->x}, {@code s->y}, {@code s->z}) and the int table through {@code SF_EXT(slot)}. That is the start
     * of the data {@link GpuSetup#table} put there. Data that depends on the world's seed goes there, never into the code, so one build
     * of a program serves every world of a pack.
     */
    public interface Gpu {
        /**
         * This extern for one dimension's GPU surface. For a rule, an OpenCL statement: a {@code return} of a state id, or nothing to
         * go on to the next rule. For a condition, an int expression. Null when the extern has no GPU form there. The surface then
         * stays on the CPU.
         */
        @Nullable
        String gpu(GpuSetup setup);
    }

    /** What {@link Gpu#gpu} may use. */
    public interface GpuSetup {
        /** The dimension's surface system (its noises and the random it draws per position). */
        SurfaceSystem system();

        /** The GPU's id of a state the rule places. */
        int state(BlockState state);

        /** Puts {@code data} into the int table. The code finds it at {@code SF_EXT(slot)} for the returned slot. */
        int table(int[] data);

        /** The world's random state. */
        RandomState randomState();

        /** OpenCL for {@code noise.getValue(x, y, z)}, the coordinates OpenCL double expressions. */
        String noise(NormalNoise noise, String x, String y, String z);

        /**
         * Adds OpenCL functions the code calls, once per {@code name} whatever the number of externs asking. They come before the
         * rules, after the surface's own helpers ({@code jd2i}, {@code jd2i_floor}).
         */
        void helper(String name, String code);
    }

    /** What a lowering may use. */
    public interface Context {
        Ir.Rule rule(SurfaceRules.RuleSource source);

        Ir.Cond cond(SurfaceRules.ConditionSource source);

        Ir.Rule extern(Rule rule);

        Ir.Cond extern(Cond cond);

        /**
         * True when every one of {@code conds} is, asked in order up to the first false one. A type whose test has a part that
         * depends on the block and a part that depends on the column only becomes the two, so the column's part is cached.
         */
        Ir.Cond all(List<Ir.Cond> conds);

        /** An assumption the lowering makes (a mod's config value). Vanilla builds a chunk for which it no longer holds. */
        void guard(BooleanSupplier holds);
    }

    @FunctionalInterface
    public interface RuleLowering {
        /** @return the IR for {@code source}, or null when it is not this lowering's type */
        Ir.Rule lower(SurfaceRules.RuleSource source, Context ctx);
    }

    @FunctionalInterface
    public interface CondLowering {
        Ir.Cond lower(SurfaceRules.ConditionSource source, Context ctx);
    }

    static final List<RuleLowering> RULES = new CopyOnWriteArrayList<>();
    static final List<CondLowering> CONDS = new CopyOnWriteArrayList<>();

    public static void register(RuleLowering lowering) {
        RULES.add(lowering);
    }

    public static void register(CondLowering lowering) {
        CONDS.add(lowering);
    }
}
