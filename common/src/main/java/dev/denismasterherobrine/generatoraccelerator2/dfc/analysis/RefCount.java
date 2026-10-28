package dev.denismasterherobrine.generatoraccelerator2.dfc.analysis;

import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.INode;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.Node;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Counts how many parent edges reach each node across a set of roots (markers are transparent). */
public final class RefCount {
    private final Map<Node, Integer> counts = new IdentityHashMap<>();
    private final List<Node> order = new ArrayList<>();

    public static RefCount of(List<Node> roots) {
        RefCount rc = new RefCount();
        for (Node r : roots) {
            rc.visit(r);
        }
        return rc;
    }

    private void visit(Node n) {
        Integer c = counts.get(n);
        if (c != null) {
            counts.put(n, c + 1);
            return;
        }
        counts.put(n, 1);
        for (Node child : children(n)) {
            visit(child);
        }
        order.add(n);
    }

    public int count(Node n) {
        Integer c = counts.get(n);
        return c == null ? 0 : c;
    }

    /** Post-order over all reachable nodes (children before parents, each once). */
    public List<Node> postOrder() {
        return order;
    }

    public static List<Node> children(Node n) {
        List<Node> out = new ArrayList<>(3);
        if (n instanceof Node.Ap2 a) {
out.add(a.a()); out.add(a.b());
        } else if (n instanceof Node.MulOrAdd m) {
            out.add(m.input());
        } else if (n instanceof Node.Mapped m) {
            out.add(m.input());
        } else if (n instanceof Node.Clamp c) {
            out.add(c.input());
        } else if (n instanceof Node.RangeChoice r) {
out.add(r.input()); out.add(r.inRange()); out.add(r.outRange());
        } else if (n instanceof Node.ShiftedNoise s) {
out.add(s.shiftX()); out.add(s.shiftY()); out.add(s.shiftZ());
        } else if (n instanceof Node.Weird w) {
            out.add(w.input());
        } else if (n instanceof Node.Spline s) {
            splineCoords(s.spline(), out);
        } else if (n instanceof Node.BlendDensity b) {
            out.add(b.input());
        } else if (n instanceof Node.Marker m) {
            out.add(m.child());
        } else if (n instanceof Node.Select s) {
condChildren(s.cond(), out); out.add(s.whenJump()); out.add(s.whenFall());
        } else if (n instanceof Node.StaticCall c) {
for (Object a : c.args()) { if (a instanceof Node nn) out.add(nn); else intChildren((INode) a, out); }
        } else if (n instanceof Node.NoiseAt na) {
out.add(na.x()); out.add(na.y()); out.add(na.z());
        } else if (n instanceof Node.IntToDouble i) {
            intChildren(i.value(), out);
        } else if (n instanceof Node.RoundFloat r) {
            out.add(r.value());
        }
        return out;
    }

    private static void condChildren(Node.Cond c, List<Node> out) {
        if (c.kind() == Node.CmpKind.ICMP) {
            intChildren(c.intA(), out);
            intChildren(c.intB(), out);
        } else {
            out.add(c.a());
            out.add(c.b());
        }
    }

    /** Double-typed nodes reachable from an integer expression (integer nodes themselves are never shared or spilled). */
    public static void intChildren(INode n, List<Node> out) {
        if (n instanceof INode.IntBin b) {
intChildren(b.a(), out); intChildren(b.b(), out);
        } else if (n instanceof INode.IntNeg g) {
            intChildren(g.a(), out);
        } else if (n instanceof INode.DoubleToInt d) {
            out.add(d.value());
        } else if (n instanceof INode.IntStaticCall c) {
for (Object a : c.args()) { if (a instanceof Node nn) out.add(nn); else intChildren((INode) a, out); }
        } else if (n instanceof INode.SelectInt s) {
condChildren(s.cond(), out); intChildren(s.whenJump(), out); intChildren(s.whenFall(), out);
        }
    }

    private static void splineCoords(Node.SplineSpec s, List<Node> out) {
        if (s instanceof Node.SplineMulti m) {
            out.add(m.coordinate());
            for (Node.SplineSpec v : m.values()) {
                splineCoords(v, out);
            }
        }
    }
}
