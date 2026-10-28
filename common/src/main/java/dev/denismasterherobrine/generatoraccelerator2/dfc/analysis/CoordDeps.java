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
        if (n instanceof Node.Const c) {
            return Dep.CONST;
        } else if (n instanceof Node.Ap2 a) {
            return of(a.a()).join(of(a.b()));
        } else if (n instanceof Node.MulOrAdd m) {
            return of(m.input());
        } else if (n instanceof Node.Mapped m) {
            return of(m.input());
        } else if (n instanceof Node.Clamp c) {
            return of(c.input());
        } else if (n instanceof Node.RangeChoice r) {
            return of(r.input()).join(of(r.inRange())).join(of(r.outRange()));
        } else if (n instanceof Node.YGradient y) {
            return Dep.Y;
        } else if (n instanceof Node.Noise no) {
            return no.yScale() == 0.0 ? Dep.XZ : Dep.XYZ;
        } else if (n instanceof Node.ShiftedNoise s) {
            return (s.yScale() == 0.0 ? Dep.XZ : Dep.XYZ).join(of(s.shiftX())).join(of(s.shiftY())).join(of(s.shiftZ()));
        } else if (n instanceof Node.ShiftA s) {
            return Dep.XZ;
        } else if (n instanceof Node.ShiftB s) {
            return Dep.XZ;
        } else if (n instanceof Node.Shift s) {
            return Dep.XYZ;
        } else if (n instanceof Node.Weird w) {
            return Dep.XYZ;
        } else if (n instanceof Node.Spline s) {
            return spline(s.spline());
        } else if (n instanceof Node.Blended b) {
            return Dep.XYZ;
        } else if (n instanceof Node.BlendDensity b) {
            return Dep.XYZ;
        } else if (n instanceof Node.BlendAlpha a) {
            return Dep.XZ;
        } else if (n instanceof Node.BlendOffset o) {
            return Dep.XZ;
        } else if (n instanceof Node.Beardifier b) {
            return Dep.XYZ;
        } else if (n instanceof Node.EndIslands e) {
            return Dep.XZ; // compute() reads blockX / 8 and blockZ / 8 only
        } else if (n instanceof Node.Marker m) {
            return marker(m);
        } else if (n instanceof Node.Unsupported u) {
            return Dep.XYZ;
        } else if (n instanceof Node.Select s) {
            return cond(s.cond()).join(of(s.whenJump())).join(of(s.whenFall()));
        } else if (n instanceof Node.StaticCall c) {
            return args(c.args());
        } else if (n instanceof Node.NoiseAt na) {
            return of(na.x()).join(of(na.y())).join(of(na.z()));
        } else if (n instanceof Node.IntToDouble i) {
            return ofInt(i.value());
        } else if (n instanceof Node.RoundFloat r) {
            return of(r.value());
        } else {
            throw new IllegalStateException(String.valueOf(n));
        }
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
        if (n instanceof INode.IntConst c) {
            return Dep.CONST;
        } else if (n instanceof INode.IntCoord c) {
            return c.axis() == 1 ? Dep.Y : Dep.XZ;
        } else if (n instanceof INode.IntBin b) {
            return ofInt(b.a()).join(ofInt(b.b()));
        } else if (n instanceof INode.IntNeg g) {
            return ofInt(g.a());
        } else if (n instanceof INode.DoubleToInt d) {
            return of(d.value());
        } else if (n instanceof INode.IntStaticCall c) {
            return args(c.args());
        } else if (n instanceof INode.SelectInt s) {
            return cond(s.cond()).join(ofInt(s.whenJump())).join(ofInt(s.whenFall()));
        } else {
            throw new IllegalStateException(String.valueOf(n));
        }
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
        String own;
        if (n instanceof Node.Noise no) {
            own = no.yScale() != 0.0 ? "noise with y_scale " + no.yScale() : null;
        } else if (n instanceof Node.ShiftedNoise s) {
            own = s.yScale() != 0.0 ? "shifted_noise with y_scale " + s.yScale() : null;
        } else if (n instanceof Node.YGradient y) {
            own = "y_clamped_gradient";
        } else if (n instanceof Node.Shift s) {
            own = "shift (3D)";
        } else if (n instanceof Node.Weird w) {
            own = "weird_scaled_sampler (3D)";
        } else if (n instanceof Node.Blended b) {
            own = "old_blended_noise";
        } else if (n instanceof Node.BlendDensity b) {
            own = "blend_density";
        } else if (n instanceof Node.Beardifier b) {
            own = "beardifier";
        } else if (n instanceof Node.Unsupported u) {
            own = "an uncompiled " + u.vanilla().getClass().getName();
        } else if (n instanceof Node.Marker m && chunkMode && m.type() != Node.MarkerType.CACHE_ONCE && m.type() != Node.MarkerType.FLAT_CACHE
                && m.type() != Node.MarkerType.CACHE_2D) {
            own = m.type().name().toLowerCase(Locale.ROOT) + " inside";
        } else {
            own = null;
        }
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
