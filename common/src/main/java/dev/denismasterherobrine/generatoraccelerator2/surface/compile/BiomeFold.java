package dev.denismasterherobrine.generatoraccelerator2.surface.compile;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * A surface program's rules for one biome, with every biome condition answered for that biome and what the answers
 * settle taken out. A block's biome is the same for every condition its rules ask ({@code SurfaceEval.biome()}). Mod
 * packs wrap nearly every rule in "is the biome one of mine" tests (TerraBlender's per-namespace rules, Terralith's
 * per-biome sequences), so a block asked dozens of them. For one biome, most of the rule tree is unreachable.
 *
 * <p>Only answers are folded, and only what cannot change a result is dropped: a test whose condition is now false (and
 * whose condition asks nothing that keeps state), a sequence's rules after one that always gives a block, the parts of an
 * all-of after a false one and of an any-of after a true one. Those parts were never asked. Conditions whose evaluation
 * can matter later stay where they were. Steepness and Lithostitched's slope are computed at their first test in a
 * column from the live heightmap, and other mods' condition objects are asked as vanilla asks them.
 */
final class BiomeFold {
    private BiomeFold() {}

    /** The rules with every biome condition answered by {@code answers} (bit = slot). Never null. */
    static Ir.Rule fold(Ir.Rule root, BitSet answers) {
        Ir.Rule r = rule(root, answers);
        return r != null ? r : new Ir.State(null);
    }

    /** The folded rule, or null when it gives nothing and asks nothing that matters. */
    private static Ir.Rule rule(Ir.Rule rule, BitSet answers) {
        return switch (rule) {
            case Ir.State s -> s.state() == null ? null : s;
            case Ir.Bands b -> b;
            case Ir.OpaqueRule o -> o;
            case Ir.ExternRule e -> e;
            case Ir.Test t -> {
                Ir.Cond c = cond(t.cond(), answers);
                if (c instanceof Ir.Const k) {
                    yield k.value() ? rule(t.then(), answers) : null;
                }
                Ir.Rule then = alwaysFalse(c) ? null : rule(t.then(), answers);
                if (then == null) {
                    // gives nothing either way: kept only to ask a condition whose asking matters
                    yield free(c) ? null : new Ir.Test(c, new Ir.State(null));
                }
                yield new Ir.Test(c, then);
            }
            case Ir.Seq seq -> {
                List<Ir.Rule> out = new ArrayList<>(seq.rules().size());
                for (Ir.Rule child : seq.rules()) {
                    Ir.Rule f = rule(child, answers);
                    if (f == null) {
                        continue;
                    }
                    out.add(f);
                    if (f instanceof Ir.State) {
                        // always gives its block: nothing after it is reached
                        break;
                    }
                }
                yield out.isEmpty() ? null : out.size() == 1 ? out.get(0) : new Ir.Seq(List.copyOf(out));
            }
        };
    }

    private static Ir.Cond cond(Ir.Cond cond, BitSet answers) {
        return switch (cond) {
            case Ir.Biome b -> new Ir.Const(b.id(), answers.get(b.slot()));
            case Ir.Not n -> {
                Ir.Cond t = cond(n.target(), answers);
                yield t instanceof Ir.Const k ? new Ir.Const(n.id(), !k.value()) : t == n.target() ? n : new Ir.Not(n.id(), t);
            }
            case Ir.All a -> parts(a.id(), a.conds(), false, answers);
            case Ir.Any a -> parts(a.id(), a.conds(), true, answers);
            default -> cond;
        };
    }

    /**
     * An all-of ({@code decides} false) or any-of ({@code decides} true) asked in order up to the first part with the deciding
     * value. Parts answered with the other value drop out. Parts after a deciding answer were never asked.
     */
    private static Ir.Cond parts(int id, List<Ir.Cond> conds, boolean decides, BitSet answers) {
        List<Ir.Cond> out = new ArrayList<>(conds.size());
        for (Ir.Cond part : conds) {
            Ir.Cond f = cond(part, answers);
            if (f instanceof Ir.Const k) {
                if (k.value() != decides) {
                    continue;
                }
                if (free(out)) {
                    return new Ir.Const(id, decides);
                }
                // the parts before it are still asked, then the answer is settled
                out.add(f);
                break;
            }
            out.add(f);
        }
        if (out.isEmpty()) {
            return new Ir.Const(id, !decides);
        }
        if (out.size() == 1) {
            return out.get(0);
        }
        return decides ? new Ir.Any(id, List.copyOf(out)) : new Ir.All(id, List.copyOf(out));
    }

    /**
     * True when an all-of ends in a false answer, or a not of an any-of ends in a true answer. Either value is false whatever
     * the parts before it say.
     */
    private static boolean alwaysFalse(Ir.Cond c) {
        return c instanceof Ir.All a && a.conds().get(a.conds().size() - 1) instanceof Ir.Const k && !k.value()
            || c instanceof Ir.Not n && n.target() instanceof Ir.Any any && any.conds().get(any.conds().size() - 1) instanceof Ir.Const k2 && k2.value();
    }

    private static boolean free(List<Ir.Cond> conds) {
        for (Ir.Cond c : conds) {
            if (!free(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * True when asking the condition (or not) changes nothing but its own answer: the pure functions of the evaluation
     * state. Steepness and slope are computed at their first test in a column from the live heightmap. Other mods'
     * conditions are their own objects.
     */
    static boolean free(Ir.Cond c) {
        return switch (c) {
            case Ir.Steep s -> false;
            case Ir.Slope s -> false;
            case Ir.OpaqueCond o -> false;
            case Ir.ExternCond e -> false;
            case Ir.Not n -> free(n.target());
            case Ir.All a -> free(a.conds());
            case Ir.Any a -> free(a.conds());
            default -> true;
        };
    }
}
