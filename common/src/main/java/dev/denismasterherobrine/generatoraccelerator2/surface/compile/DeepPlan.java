package dev.denismasterherobrine.generatoraccelerator2.surface.compile;

import dev.denismasterherobrine.generatoraccelerator2.platform.PlatformHooks;
import java.util.List;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What the rules can give below a column's preliminary surface ({@code blockY < minSurfaceLevel}),
 * per block y. There {@code abovePreliminarySurface()} is false, vertical gradients are constant
 * outside their band, and y checks without stone depth or surface depth are constant. So for most y
 * the whole rule tree folds to a constant (vanilla: deepslate below 0, nothing above 8).
 *
 * <p>Per y the plan has one of three kinds. {@link #CONST} holds the folded result, possibly
 * {@code null}. {@link #EVAL} means the rules must run, but every condition they can reach depends
 * only on the block, the column and pure randomness: no stone-depth or water counters, no other
 * mod's rule objects, no steepness. {@link #FULL} means the normal per-block path is needed. A column
 * switches to the deep path at the first block below its preliminary surface from which every lower
 * y is CONST or EVAL. There the counters vanilla keeps are never read, so they need not be kept.
 */
public final class DeepPlan {
    public static final byte CONST = 0;
    public static final byte EVAL = 1;
    public static final byte FULL = 2;

    public final int minY;
    private final byte[] kind;
    private final BlockState[] state;
    /** {@code okBelow[y - minY]}: every y' <= y is CONST or EVAL. */
    private final boolean[] okBelow;
    /** {@code active[y - minY]}: the highest y' <= y whose plan can change a block (not CONST null), or {@code minY - 1}. */
    private final int[] active;

    private DeepPlan(int minY, int maxY) {
        this.minY = minY;
        int n = maxY - minY;
        this.kind = new byte[n];
        this.state = new BlockState[n];
        this.okBelow = new boolean[n];
        this.active = new int[n];
    }

    /** The highest y at or below {@code y} where the plan may write (y itself when it may), or {@code minY - 1}. */
    public int activeAtOrBelow(int y) {
        int i = y - minY;
        if (i >= active.length) {
            return y;
        }
        return i < 0 ? minY - 1 : active[i];
    }

    public byte kind(int y) {
        return kind[y - minY];
    }

    public BlockState state(int y) {
        return state[y - minY];
    }

    /** True when the deep path may start at {@code y} (inside the chunk's build range). */
    public boolean okAtOrBelow(int y) {
        int i = y - minY;
        return i >= 0 && i < okBelow.length && okBelow[i];
    }

    static DeepPlan build(SurfaceProgram program, int minY, int maxY, int[] gradLo, int[] gradHi, int[] anchorY) {
        DeepPlan plan = new DeepPlan(minY, maxY);
        boolean ok = true;
        for (int y = minY; y < maxY; y++) {
            Folded f = fold(program.root, y, gradLo, gradHi, anchorY);
            int i = y - minY;
            if (f.constant) {
                plan.kind[i] = CONST;
                plan.state[i] = f.state;
            } else {
                plan.kind[i] = f.impure ? FULL : EVAL;
            }
            ok &= plan.kind[i] != FULL;
            plan.okBelow[i] = ok;
            boolean writes = plan.kind[i] != CONST || plan.state[i] != null;
            plan.active[i] = writes ? y : (i == 0 ? minY - 1 : plan.active[i - 1]);
        }
        return plan;
    }

    /**
     * True when every y of the 16-block section starting at {@code sectionMinY} is CONST and each
     * folded state, written over {@code defaultBlock}, keeps the section's counters (non-empty,
     * randomly ticking, fluid) unchanged and carries no fluid (so no post-processing mark). Then the
     * section's default blocks can be rewritten in one pass over its storage.
     */
    public boolean bulkSection(int sectionMinY, BlockState defaultBlock) {
        for (int y = sectionMinY; y < sectionMinY + 16; y++) {
            int i = y - minY;
            if (i < 0 || i >= kind.length || kind[i] != CONST) {
                return false;
            }
            BlockState s = state[i];
            if (s != null && (PlatformHooks.isEmpty(s) != PlatformHooks.isEmpty(defaultBlock) || s.isRandomlyTicking() != defaultBlock.isRandomlyTicking()
                || !s.getFluidState().isEmpty() || !defaultBlock.getFluidState().isEmpty())) {
                return false;
            }
        }
        return true;
    }

    /** A folded rule: constant (state may be null) or residual. Impure when it can reach counters, opaque objects or steepness. */
    private record Folded(boolean constant, BlockState state, boolean impure) {
        static final Folded NULL = new Folded(true, null, false);
    }

    private static final byte TRUE = 1;
    private static final byte FALSE = 0;
    private static final byte UNKNOWN = 2;

    /** Folded condition: TRUE, FALSE or UNKNOWN, with whether evaluating it can reach impure inputs. */
    private record FoldedCond(byte value, boolean impure) {}

    private static Folded fold(Ir.Rule rule, int y, int[] gradLo, int[] gradHi, int[] anchorY) {
        switch (rule) {
            case Ir.State s -> {
                return new Folded(true, s.state(), false);
            }
            case Ir.Bands b -> {
                return new Folded(false, null, false);
            }
            case Ir.ExternRule e -> {
                // compat code is residual. Positional code may stay below the surface like the bands
                return new Folded(false, null, !e.positional());
            }
            case Ir.OpaqueRule o -> {
                return new Folded(false, null, true);
            }
            case Ir.Test t -> {
                FoldedCond c = fold(t.cond(), y, gradLo, gradHi, anchorY);
                if (c.value() == FALSE) {
                    return Folded.NULL;
                }
                Folded then = fold(t.then(), y, gradLo, gradHi, anchorY);
                if (c.value() == TRUE) {
                    return then;
                }
                if (then.constant() && then.state() == null && skippable(t.cond())) {
                    // the condition only chooses between nothing and nothing
                    return Folded.NULL;
                }
                return new Folded(false, null, c.impure() || then.impure());
            }
            case Ir.Seq seq -> {
                return foldSeq(seq.rules(), 0, y, gradLo, gradHi, anchorY);
            }
        }
    }

    /** {@code rules[from..]} as a sequence: the first rule giving a block wins. */
    private static Folded foldSeq(List<Ir.Rule> rules, int from, int y, int[] gradLo, int[] gradHi, int[] anchorY) {
        boolean residual = false;
        boolean impure = false;
        for (int k = from; k < rules.size(); k++) {
            Ir.Rule r = rules.get(k);
            Folded f = fold(r, y, gradLo, gradHi, anchorY);
            if (f.constant()) {
                if (f.state() == null) {
                    continue;
                }
                if (!residual) {
                    return f;
                }
                return new Folded(false, null, impure);
            }
            if (!residual && r instanceof Ir.Test t && skippable(t.cond())) {
                // a test giving the block that the rest of the sequence gives anyway. Its condition does not matter. Examples
                // are TerraBlender's per-namespace tests, which all end in the same deepslate and bedrock below the surface
                Folded then = fold(t.then(), y, gradLo, gradHi, anchorY);
                if (then.constant() && then.state() != null) {
                    Folded rest = foldSeq(rules, k + 1, y, gradLo, gradHi, anchorY);
                    if (rest.constant() && rest.state() == then.state()) {
                        return rest;
                    }
                }
            }
            residual = true;
            impure |= f.impure();
        }
        return residual ? new Folded(false, null, impure) : Folded.NULL;
    }

    /**
     * True when a condition need not be evaluated where its result cannot change the block. Every condition GA2
     * lowers is a side-effect-free function of the evaluation state. Steepness is cached per column, and the deep path
     * never reads it. Another mod's condition object is always asked, as vanilla asks it.
     */
    private static boolean skippable(Ir.Cond cond) {
        return switch (cond) {
            case Ir.OpaqueCond o -> false;
            case Ir.ExternCond e -> e.positional();
            case Ir.Not n -> skippable(n.target());
            case Ir.All a -> a.conds().stream().allMatch(DeepPlan::skippable);
            case Ir.Any a -> a.conds().stream().allMatch(DeepPlan::skippable);
            default -> true;
        };
    }

    /**
     * An all-of ({@code decides} false) or any-of ({@code decides} true). It is settled by the first part that folds to the
     * deciding value when every part before it may go unasked. It takes the other value when every part folds to that value.
     */
    private static FoldedCond foldParts(List<Ir.Cond> parts, byte decides, int y, int[] gradLo, int[] gradHi, int[] anchorY) {
        byte other = decides == TRUE ? FALSE : TRUE;
        boolean allOther = true;
        boolean skipped = true;
        boolean impure = false;
        for (Ir.Cond part : parts) {
            FoldedCond f = fold(part, y, gradLo, gradHi, anchorY);
            if (f.value() == decides && skipped) {
                return new FoldedCond(decides, false);
            }
            allOther &= f.value() == other;
            skipped &= skippable(part);
            impure |= f.impure();
        }
        return allOther ? new FoldedCond(other, false) : new FoldedCond(UNKNOWN, impure);
    }

    private static FoldedCond fold(Ir.Cond cond, int y, int[] gradLo, int[] gradHi, int[] anchorY) {
        return switch (cond) {
            case Ir.AboveSurface a -> new FoldedCond(FALSE, false);
            case Ir.Gradient g -> new FoldedCond(y <= gradLo[g.slot()] ? TRUE : y >= gradHi[g.slot()] ? FALSE : UNKNOWN, false);
            case Ir.YCheck c -> c.addStone() ? new FoldedCond(UNKNOWN, true)
                : c.mult() == 0 ? new FoldedCond(y >= anchorY[c.anchor()] ? TRUE : FALSE, false)
                : new FoldedCond(UNKNOWN, false);
            case Ir.Water w -> new FoldedCond(UNKNOWN, true);
            case Ir.StoneDepth d -> new FoldedCond(UNKNOWN, true);
            case Ir.Steep s -> new FoldedCond(UNKNOWN, true);
            case Ir.Slope s -> new FoldedCond(UNKNOWN, true);
            case Ir.OpaqueCond o -> new FoldedCond(UNKNOWN, true);
            case Ir.ExternCond e -> new FoldedCond(UNKNOWN, !e.positional());
            case Ir.Hole h -> new FoldedCond(UNKNOWN, false);
            case Ir.Noise n -> new FoldedCond(UNKNOWN, false);
            case Ir.Biome b -> new FoldedCond(UNKNOWN, false);
            case Ir.Temperature t -> new FoldedCond(UNKNOWN, false);
            case Ir.Const c -> new FoldedCond(c.value() ? TRUE : FALSE, false);
            case Ir.Not n -> {
                FoldedCond inner = fold(n.target(), y, gradLo, gradHi, anchorY);
                yield new FoldedCond(inner.value() == UNKNOWN ? UNKNOWN : inner.value() == TRUE ? FALSE : TRUE, inner.impure());
            }
            case Ir.All a -> foldParts(a.conds(), FALSE, y, gradLo, gradHi, anchorY);
            case Ir.Any a -> foldParts(a.conds(), TRUE, y, gradLo, gradHi, anchorY);
        };
    }
}
