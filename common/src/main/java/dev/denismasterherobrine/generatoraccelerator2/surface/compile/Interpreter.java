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
        switch (rule) {
            case Ir.Seq seq -> {
                for (Ir.Rule r : seq.rules()) {
                    BlockState state = rule(r, s);
                    if (state != null) {
                        return state;
                    }
                }
                return null;
            }
            case Ir.Test test -> {
                return cond(test.cond(), s) ? rule(test.then(), s) : null;
            }
            case Ir.State state -> {
                return state.state();
            }
            case Ir.Bands bands -> {
                return s.bands();
            }
            case Ir.OpaqueRule opaque -> {
                return s.opaqueRule(opaque.index());
            }
            case Ir.ExternRule extern -> {
                return s.externRule(extern.index());
            }
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
        return switch (cond) {
            case Ir.Biome b -> s.biome(b.slot());
            case Ir.Noise n -> s.noise(n.slot(), n.min(), n.max());
            case Ir.Gradient g -> s.gradient(g.slot());
            case Ir.YCheck y -> s.yCheck(y.anchor(), y.mult(), y.addStone());
            case Ir.Water w -> s.water(w.offset(), w.mult(), w.addStone());
            case Ir.StoneDepth d -> s.stoneDepth(d.offset(), d.addSurface(), d.secondaryRange(), d.ceiling());
            case Ir.Steep st -> s.steep();
            case Ir.Slope sl -> s.slope(sl.min(), sl.max());
            case Ir.Hole h -> s.hole();
            case Ir.AboveSurface a -> s.aboveSurface();
            case Ir.Temperature t -> s.temperature();
            case Ir.Not n -> !cond(n.target(), s);
            case Ir.All a -> cond(a, s);
            case Ir.Any a -> cond(a, s);
            case Ir.OpaqueCond o -> s.opaqueCond(o.index());
            case Ir.ExternCond e -> s.externCond(e.index());
            case Ir.Const c -> c.value();
        };
    }
}
