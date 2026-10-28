package dev.denismasterherobrine.generatoraccelerator2.dfc.ir;

import dev.denismasterherobrine.generatoraccelerator2.api.DensityFunctionLowering;
import dev.denismasterherobrine.generatoraccelerator2.dfc.analysis.CoordDeps;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A lowered function evaluated at other block coordinates. Mods evaluate a child at a position of their own, e.g.
 * {@code child.compute(SimpleContext.of(x + dx, y, z))}. The result reads the given int expressions (of the outer position) in
 * place of its own coordinates. Implicit coordinate reads become explicit ones: {@code Noise} becomes {@code NoiseAt} with the
 * scaled coordinates, {@code y_clamped_gradient} becomes {@code Mth.clampedMap} of y. The analyses and both code generators then
 * treat the result like any other graph.
 *
 * <p>Inside a {@code NoiseChunk}, the child's cache markers are the chunk's wrappers. A wrapper asked about another position
 * computes its function there ({@code interpolated}, {@code cache_once}, {@code cache_all_in_cell}). A {@code cache_2d} remembers
 * its last value by x and z, so it is transparent only over a function of x and z. A {@code flat_cache} returns the chunk's stored
 * value when the position lies in the chunk's grid. That needs the chunk's origin, so a function under one is not rewritten.
 * Neither is anything that reads chunk state at the position (blending, the beardifier) or has no explicit form. The caller then
 * keeps the original object.
 *
 * <p>Every rewritten node keeps the bounds of the node it replaces. Vanilla's {@code min} and {@code max} skip their second
 * argument by its bounds, so a parent must read the same bounds. Where the explicit form has other bounds, it is wrapped in
 * {@code x * 1.0} with the original bounds. That multiplication is the identity on every double.
 */
public final class CoordSubstitution {
    private static final String MTH = "net/minecraft/util/Mth";
    private static final String CLAMPED_MAP = "(DDDDD)D";

    private final DensityFunctionLowering.Context ctx;
    private final INode x, y, z;
    private final Node xd, yd, zd;
    private final Map<Node, Node> memo = new IdentityHashMap<>();
    private final CoordDeps deps = new CoordDeps(false);

    private CoordSubstitution(DensityFunctionLowering.Context ctx, INode x, INode y, INode z) {
        this.ctx = ctx;
        this.x = x;
        this.y = y;
        this.z = z;
        this.xd = ctx.intern(new Node.IntToDouble(x));
        this.yd = ctx.intern(new Node.IntToDouble(y));
        this.zd = ctx.intern(new Node.IntToDouble(z));
    }

    /** Why a function could not be rewritten. */
    public static final class Declined extends Exception {
        Declined(String reason) {
            super(reason, null, false, false);
        }
    }

    /**
     * {@code n} evaluated at block {@code (x, y, z)}.
     *
     * @throws Declined when a node of {@code n} has no exact form at another position
     */
    public static Node at(Node n, INode x, INode y, INode z, DensityFunctionLowering.Context ctx) throws Declined {
        return new CoordSubstitution(ctx, x, y, z).node(n);
    }

    private Node node(Node n) throws Declined {
        Node done = memo.get(n);
        if (done != null) {
            return done;
        }
        Node r = bounded(rewrite(n), n);
        memo.put(n, r);
        return r;
    }

    /** {@code r} with the bounds of {@code original}. */
    private Node bounded(Node r, Node original) {
        if (same(r.min(), original.min()) && same(r.max(), original.max())) {
            return r;
        }
        return ctx.intern(new Node.MulOrAdd(true, r, 1.0, original.min(), original.max()));
    }

    private static boolean same(double a, double b) {
        return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
    }

    private Node rewrite(Node n) throws Declined {
        if (n instanceof Node.Const c) {
            return c;
        } else if (n instanceof Node.Ap2 a) {
            return ctx.intern(new Node.Ap2(a.type(), node(a.a()), node(a.b()), a.min(), a.max()));
        } else if (n instanceof Node.MulOrAdd m) {
            return ctx.intern(new Node.MulOrAdd(m.mul(), node(m.input()), m.argument(), m.min(), m.max()));
        } else if (n instanceof Node.Mapped m) {
            return ctx.intern(new Node.Mapped(m.type(), node(m.input()), m.min(), m.max()));
        } else if (n instanceof Node.Clamp c) {
            return ctx.intern(new Node.Clamp(node(c.input()), c.lo(), c.hi()));
        } else if (n instanceof Node.RangeChoice r) {
            return ctx.intern(new Node.RangeChoice(node(r.input()), r.minInclusive(), r.maxExclusive(), node(r.inRange()), node(r.outRange()),
            r.min(), r.max()));
        } else if (n instanceof Node.YGradient g) {
            if (g.fromY() == g.toY()) {
                throw new Declined("y_clamped_gradient with from_y == to_y");
            }
            // YClampedGradient.compute: Mth.clampedMap((double) y, (double) fromY, (double) toY, fromValue, toValue)
            return ctx.intern(new Node.StaticCall(Names.runtimeClass(MTH), Names.runtimeMethod(MTH, "clampedMap", CLAMPED_MAP), Names.runtimeDesc(CLAMPED_MAP),
                List.of(yd, constant(g.fromY()), constant(g.toY()), constant(g.fromValue()), constant(g.toValue()))));
        } else if (n instanceof Node.Noise no) {
            // Noise.compute: getValue(x * xzScale, y * yScale, z * xzScale)
            return ctx.intern(new Node.NoiseAt(no.spec(), scaled(xd, no.xzScale()), flatY(no.yScale()), scaled(zd, no.xzScale()), no.max()));
        } else if (n instanceof Node.ShiftedNoise s) {
            // ShiftedNoise.compute: getValue(x * xzScale + shiftX, y * yScale + shiftY, z * xzScale + shiftZ)
            return ctx.intern(new Node.NoiseAt(s.spec(), shifted(xd, s.xzScale(), node(s.shiftX())), shifted(yd, s.yScale(), node(s.shiftY())),
            shifted(zd, s.xzScale(), node(s.shiftZ())), s.max()));
        } else if (n instanceof Node.ShiftA s) {
            // ShiftNoise.compute(x, y, z): getValue(x * 0.25, y * 0.25, z * 0.25) * 4.0. ShiftA passes (x, 0, z), ShiftB (z, x, 0)
            return offset(s.spec(), s.max(), xd, null, zd);
        } else if (n instanceof Node.ShiftB s) {
            return offset(s.spec(), s.max(), zd, xd, null);
        } else if (n instanceof Node.Shift s) {
            return offset(s.spec(), s.max(), xd, yd, zd);
        } else if (n instanceof Node.Spline s) {
            return ctx.intern(new Node.Spline(spline(s.spline()), s.min(), s.max()));
        } else if (n instanceof Node.Marker m) {
            return marker(m);
        } else if (n instanceof Node.Select s) {
            return ctx.intern(new Node.Select(cond(s.cond()), node(s.whenJump()), node(s.whenFall())));
        } else if (n instanceof Node.StaticCall c) {
            return ctx.intern(new Node.StaticCall(c.owner(), c.name(), c.desc(), args(c.args())));
        } else if (n instanceof Node.NoiseAt na) {
            return ctx.intern(new Node.NoiseAt(na.spec(), node(na.x()), node(na.y()), node(na.z()), na.max()));
        } else if (n instanceof Node.IntToDouble i) {
            return ctx.intern(new Node.IntToDouble(integer(i.value())));
        } else if (n instanceof Node.RoundFloat r) {
            return ctx.intern(new Node.RoundFloat(node(r.value())));
        } else if (n instanceof Node.Weird w) {
            throw new Declined("weird_scaled_sampler");
        } else if (n instanceof Node.Blended b) {
            throw new Declined("old_blended_noise");
        } else if (n instanceof Node.BlendDensity b) {
            throw new Declined("blend_density (reads the chunk's blending)");
        } else if (n instanceof Node.BlendAlpha a) {
            throw new Declined("blend_alpha (reads the chunk's blending)");
        } else if (n instanceof Node.BlendOffset o) {
            throw new Declined("blend_offset (reads the chunk's blending)");
        } else if (n instanceof Node.Beardifier b) {
            throw new Declined("beardifier (reads the chunk's structures)");
        } else if (n instanceof Node.EndIslands e) {
            throw new Declined("end_islands");
        } else if (n instanceof Node.Unsupported u) {
            throw new Declined("an uncompiled " + u.vanilla().getClass().getName());
        } else {
            throw new IllegalStateException(String.valueOf(n));
        }
    }

    private Node marker(Node.Marker m) throws Declined {
        return switch (m.type()) {
            // NoiseChunk's wrappers compute their function at any other position
            case INTERPOLATED, CACHE_ONCE, CACHE_ALL_IN_CELL -> node(m.child());
            case CACHE_2D -> {
                if (!deps.of(m.child()).isXzOrConst()) {
                    throw new Declined("cache_2d over a function of y (the wrapper's last value depends on order)");
                }
                yield node(m.child());
            }
            case FLAT_CACHE -> throw new Declined("flat_cache (inside the chunk's grid, the wrapper returns the chunk's stored value)");
        };
    }

    private Node.SplineSpec spline(Node.SplineSpec s) throws Declined {
        if (s instanceof Node.SplineConst c) {
            return c;
        }
        Node.SplineMulti m = (Node.SplineMulti) s;
        List<Node.SplineSpec> values = new ArrayList<>(m.values().size());
        for (Node.SplineSpec v : m.values()) {
            values.add(spline(v));
        }
        return new Node.SplineMulti(node(m.coordinate()), m.locations(), values, m.derivatives());
    }

    private Node.Cond cond(Node.Cond c) throws Declined {
        return new Node.Cond(c.kind(), c.op(), c.a() == null ? null : node(c.a()), c.b() == null ? null : node(c.b()),
            c.intA() == null ? null : integer(c.intA()), c.intB() == null ? null : integer(c.intB()));
    }

    private List<Object> args(List<Object> args) throws Declined {
        List<Object> out = new ArrayList<>(args.size());
        for (Object a : args) {
            out.add(a instanceof Node n ? node(n) : a instanceof INode i ? integer(i) : a);
        }
        return out;
    }

    private INode integer(INode n) throws Declined {
        if (n instanceof INode.IntConst c) {
            return c;
        } else if (n instanceof INode.IntCoord c) {
            return c.axis() == 0 ? x : c.axis() == 1 ? y : z;
        } else if (n instanceof INode.IntBin b) {
            return new INode.IntBin(b.op(), integer(b.a()), integer(b.b()));
        } else if (n instanceof INode.IntNeg g) {
            return new INode.IntNeg(integer(g.a()));
        } else if (n instanceof INode.DoubleToInt d) {
            return new INode.DoubleToInt(node(d.value()));
        } else if (n instanceof INode.IntStaticCall c) {
            return new INode.IntStaticCall(c.owner(), c.name(), c.desc(), args(c.args()));
        } else if (n instanceof INode.SelectInt s) {
            return new INode.SelectInt(cond(s.cond()), integer(s.whenJump()), integer(s.whenFall()));
        } else {
            throw new IllegalStateException(String.valueOf(n));
        }
    }

    private Node constant(double v) {
        return ctx.intern(new Node.Const(v));
    }

    private Node scaled(Node coordinate, double scale) {
        return ctx.intern(new Node.StaticCall("$op", "dmul", "(DD)D", List.of(coordinate, constant(scale))));
    }

    /**
     * {@code y * yScale}. With a y scale of 0, the product is +0.0 or -0.0. A noise adds its own nonzero offset to the coordinate
     * first, so both give the same value. The compiler already treats such a noise as a function of x and z ({@link CoordDeps}).
     */
    private Node flatY(double yScale) {
        return yScale == 0.0 ? constant(0.0) : scaled(yd, yScale);
    }

    /** {@code coordinate * scale + shift}. With a scale of 0 and a constant shift other than -0.0, that is the shift, bit for bit. */
    private Node shifted(Node coordinate, double scale, Node shift) {
        if (scale == 0.0 && shift instanceof Node.Const c && Double.doubleToRawLongBits(c.value()) != Double.doubleToRawLongBits(-0.0)) {
            return shift;
        }
        return ctx.intern(new Node.StaticCall("$op", "dadd", "(DD)D", List.of(scaled(coordinate, scale), shift)));
    }

    /** An offset noise at (a, b, c) times 0.25, times 4.0. A null coordinate is the constant 0.0 ({@code 0.0 * 0.25}). */
    private Node offset(int spec, double max, Node a, Node b, Node c) {
        Node pa = a == null ? constant(0.0) : scaled(a, 0.25), pb = b == null ? constant(0.0) : scaled(b, 0.25), pc = c == null ? constant(0.0) : scaled(c, 0.25);
        // the node's max is the noise's max times 4.0, a power of two
        Node value = ctx.intern(new Node.NoiseAt(spec, pa, pb, pc, max / 4.0));
        return ctx.intern(new Node.StaticCall("$op", "dmul", "(DD)D", List.of(value, constant(4.0))));
    }
}
