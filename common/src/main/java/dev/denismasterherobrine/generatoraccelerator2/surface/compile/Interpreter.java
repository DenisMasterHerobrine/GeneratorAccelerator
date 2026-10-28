package dev.denismasterherobrine.generatoraccelerator2.surface.compile;

import net.minecraft.world.level.block.state.BlockState;

/**
 * Tree-walking evaluator of a {@link SurfaceProgram}: the reference the compiled evaluator is checked
 * against, and the evaluator used until (or instead of) compilation. Walks rules in vanilla order: the
 * first non-null of a sequence, and a test's condition before its follow-up. Caches pure condition
 * values per block or per column by condition id.
 */
public final class Interpreter implements SurfaceProgram.Evaluator {
    private final SurfaceProgram program;

    public Interpreter(SurfaceProgram program) {
        this.program = program;
    }

    @Override
    public BlockState apply(SurfaceEval s) {
        return rule(program.root, s);
    }

    static BlockState rule(Ir.Rule rule, SurfaceEval s) {
        if (rule instanceof Ir.Seq seq) {
            for (Ir.Rule r : seq.rules()) {
                BlockState state = rule(r, s);
                if (state != null) {
                    return state;
                }
            }
            return null;
        } else if (rule instanceof Ir.Test test) {
            return cond(test.cond(), s) ? rule(test.then(), s) : null;
        } else if (rule instanceof Ir.State state) {
            return state.state();
        } else if (rule instanceof Ir.Bands bands) {
            return s.bands();
        } else if (rule instanceof Ir.OpaqueRule opaque) {
            return s.opaqueRule(opaque.index());
        } else if (rule instanceof Ir.ExternRule extern) {
            return s.externRule(extern.index());
        } else {
            throw new IllegalStateException(String.valueOf(rule));
        }
    }

    static boolean cond(Ir.Cond cond, SurfaceEval s) {
        if (cond instanceof Ir.Const c) {
            return c.value();
        }
        if (cond instanceof Ir.OpaqueCond opaque) {
            return s.opaqueCond(opaque.index());
        }
        if (cond instanceof Ir.Not not) {
            return !cond(not.target(), s);
        }
        if (cond instanceof Ir.All all) {
            // not cached as a whole: each part keeps its own value, and an opaque part is asked again as vanilla asks it
            for (Ir.Cond c : all.conds()) {
                if (!cond(c, s)) {
                    return false;
                }
            }
            return true;
        }
        if (cond instanceof Ir.Any any) {
            for (Ir.Cond c : any.conds()) {
                if (cond(c, s)) {
                    return true;
                }
            }
            return false;
        }
        int id = cond.id();
        int stamp = Ir.scope(cond) == Ir.Scope.COLUMN ? s.columnStamp : s.blockStamp;
        if (s.cacheStamp[id] == stamp) {
            return s.cache[id] != 0;
        }
        boolean value = compute(cond, s);
        s.cacheStamp[id] = stamp;
        s.cache[id] = (byte) (value ? 1 : 0);
        return value;
    }

    static boolean compute(Ir.Cond cond, SurfaceEval s) {
        if (cond instanceof Ir.Biome b) {
            return s.biome(b.slot());
        } else if (cond instanceof Ir.Noise n) {
            return s.noise(n.slot(), n.min(), n.max());
        } else if (cond instanceof Ir.Gradient g) {
            return s.gradient(g.slot());
        } else if (cond instanceof Ir.YCheck y) {
            return s.yCheck(y.anchor(), y.mult(), y.addStone());
        } else if (cond instanceof Ir.Water w) {
            return s.water(w.offset(), w.mult(), w.addStone());
        } else if (cond instanceof Ir.StoneDepth d) {
            return s.stoneDepth(d.offset(), d.addSurface(), d.secondaryRange(), d.ceiling());
        } else if (cond instanceof Ir.Steep st) {
            return s.steep();
        } else if (cond instanceof Ir.Slope sl) {
            return s.slope(sl.min(), sl.max());
        } else if (cond instanceof Ir.Hole h) {
            return s.hole();
        } else if (cond instanceof Ir.AboveSurface a) {
            return s.aboveSurface();
        } else if (cond instanceof Ir.Temperature t) {
            return s.temperature();
        } else if (cond instanceof Ir.Not n) {
            return !cond(n.target(), s);
        } else if (cond instanceof Ir.All a) {
            return cond(a, s);
        } else if (cond instanceof Ir.Any a) {
            return cond(a, s);
        } else if (cond instanceof Ir.OpaqueCond o) {
            return s.opaqueCond(o.index());
        } else if (cond instanceof Ir.ExternCond e) {
            return s.externCond(e.index());
        } else if (cond instanceof Ir.Const c) {
            return c.value();
        } else {
            throw new IllegalStateException(String.valueOf(cond));
        }
    }
}
