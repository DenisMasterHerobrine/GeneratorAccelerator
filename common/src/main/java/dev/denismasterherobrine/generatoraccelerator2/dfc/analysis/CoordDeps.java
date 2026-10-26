package dev.denismasterherobrine.generatoraccelerator2.dfc.analysis;

import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.INode;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.Node;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which block coordinates each node reads. Used to hoist XZ-only work out of the per-corner loop.
 * Externs and stateful nodes always count as XYZ so they are never hoisted.
 *
 * <p>In chunk mode, cache markers are classified by how the column program evaluates them.
 * Flat caches and blend caches read only (x, z). Nested interpolators, cell caches and
 * y-dependent 2D caches are opaque chunk state (XYZ). Once-caches are transparent.
 */
public final class CoordDeps {
    public enum Dep {
        CONST(0), Y(1), XZ(2), XYZ(3);

        final int bits;

        Dep(int bits) {
            this.bits = bits;
        }

        public Dep join(Dep other) {
            return of(this.bits | other.bits);
        }

        public boolean isXzOrConst() {
            return this == CONST || this == XZ;
        }

        static Dep of(int bits) {
            return switch (bits) {
                case 0 -> CONST;
                case 1 -> Y;
                case 2 -> XZ;
                default -> XYZ;
            };
        }
    }

    private final Map<Node, Dep> deps = new IdentityHashMap<>();
    private final boolean chunkMode;

    public CoordDeps(boolean chunkMode) {
        this.chunkMode = chunkMode;
    }

    public Dep of(Node n) {
        Dep d = deps.get(n);
        if (d == null) {
            d = compute(n);
            deps.put(n, d);
        }
        return d;
    }

    private Dep compute(Node n) {
        return switch (n) {
            case Node.Const c -> Dep.CONST;
            case Node.Ap2 a -> of(a.a()).join(of(a.b()));
            case Node.MulOrAdd m -> of(m.input());
            case Node.Mapped m -> of(m.input());
            case Node.Clamp c -> of(c.input());
            case Node.RangeChoice r -> of(r.input()).join(of(r.inRange())).join(of(r.outRange()));
            case Node.YGradient y -> Dep.Y;
            case Node.Noise no -> no.yScale() == 0.0 ? Dep.XZ : Dep.XYZ;
            case Node.ShiftedNoise s -> (s.yScale() == 0.0 ? Dep.XZ : Dep.XYZ).join(of(s.shiftX())).join(of(s.shiftY())).join(of(s.shiftZ()));
            case Node.ShiftA s -> Dep.XZ;
            case Node.ShiftB s -> Dep.XZ;
            case Node.Shift s -> Dep.XYZ;
            case Node.Weird w -> Dep.XYZ;
            case Node.Spline s -> spline(s.spline());
            case Node.Blended b -> Dep.XYZ;
            case Node.BlendDensity b -> Dep.XYZ;
            case Node.BlendAlpha a -> Dep.XZ;
            case Node.BlendOffset o -> Dep.XZ;
            case Node.Beardifier b -> Dep.XYZ;
            case Node.EndIslands e -> Dep.XZ;   // compute() reads blockX / 8 and blockZ / 8 only
            case Node.Marker m -> marker(m);
            case Node.Unsupported u -> Dep.XYZ;
            case Node.Select s -> cond(s.cond()).join(of(s.whenJump())).join(of(s.whenFall()));
            case Node.StaticCall c -> args(c.args());
            case Node.NoiseAt na -> of(na.x()).join(of(na.y())).join(of(na.z()));
            case Node.IntToDouble i -> ofInt(i.value());
            case Node.RoundFloat r -> of(r.value());
        };
    }

    private Dep cond(Node.Cond c) {
        return c.kind() == Node.CmpKind.ICMP ? ofInt(c.intA()).join(ofInt(c.intB())) : of(c.a()).join(of(c.b()));
    }

    private Dep args(List<Object> args) {
        Dep d = Dep.CONST;
        for (Object a : args) {
            d = d.join(a instanceof Node n ? of(n) : ofInt((INode) a));
        }
        return d;
    }

    public Dep ofInt(INode n) {
        return switch (n) {
            case INode.IntConst c -> Dep.CONST;
            case INode.IntCoord c -> c.axis() == 1 ? Dep.Y : Dep.XZ;
            case INode.IntBin b -> ofInt(b.a()).join(ofInt(b.b()));
            case INode.IntNeg g -> ofInt(g.a());
            case INode.DoubleToInt d -> of(d.value());
            case INode.IntStaticCall c -> args(c.args());
            case INode.SelectInt s -> cond(s.cond()).join(ofInt(s.whenJump())).join(ofInt(s.whenFall()));
        };
    }

    private Dep marker(Node.Marker m) {
        if (!chunkMode) {
            return of(m.child());
        }
        return switch (m.type()) {
            case CACHE_ONCE -> of(m.child());
            case FLAT_CACHE -> Dep.XZ;
            case CACHE_2D -> of(m.child()).isXzOrConst() ? of(m.child()) : Dep.XYZ;
            case INTERPOLATED, CACHE_ALL_IN_CELL -> Dep.XYZ;
        };
    }

    private Dep spline(Node.SplineSpec s) {
        if (s instanceof Node.SplineConst) {
            return Dep.CONST;
        }
        Node.SplineMulti m = (Node.SplineMulti) s;
        Dep d = of(m.coordinate());
        for (Node.SplineSpec v : m.values()) {
            d = d.join(spline(v));
        }
        return d;
    }

    /** The first node below {@code n} that makes it read y (for reports), or null when it does not. */
    public String firstReadingY(Node n) {
        return firstReadingY(n, new IdentityHashMap<>());
    }

    private String firstReadingY(Node n, Map<Node, Boolean> seen) {
        if (of(n).isXzOrConst() || seen.put(n, Boolean.TRUE) != null) {
            return null;
        }
        String own = switch (n) {
            case Node.Noise no -> no.yScale() != 0.0 ? "noise with y_scale " + no.yScale() : null;
            case Node.ShiftedNoise s -> s.yScale() != 0.0 ? "shifted_noise with y_scale " + s.yScale() : null;
            case Node.YGradient y -> "y_clamped_gradient";
            case Node.Shift s -> "shift (3D)";
            case Node.Weird w -> "weird_scaled_sampler (3D)";
            case Node.Blended b -> "old_blended_noise";
            case Node.BlendDensity b -> "blend_density";
            case Node.Beardifier b -> "beardifier";
            case Node.Unsupported u -> "an uncompiled " + u.vanilla().getClass().getName();
            case Node.Marker m when chunkMode && m.type() != Node.MarkerType.CACHE_ONCE && m.type() != Node.MarkerType.FLAT_CACHE
                && m.type() != Node.MarkerType.CACHE_2D -> m.type().name().toLowerCase(Locale.ROOT) + " inside";
            default -> null;
        };
        if (own != null) {
            return own;
        }
        for (Node c : RefCount.children(n)) {
            String r = firstReadingY(c, seen);
            if (r != null) {
                return r;
            }
        }
        return "a coordinate read (" + Names.mojangSimpleName(n.getClass()) + ")";
    }
}
