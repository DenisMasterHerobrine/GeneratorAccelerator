package dev.denismasterherobrine.generatoraccelerator2.dfc.ir;

import dev.denismasterherobrine.generatoraccelerator2.api.DensityFunctionLowering;
import dev.denismasterherobrine.generatoraccelerator2.dfc.DfcConfig;
import dev.denismasterherobrine.generatoraccelerator2.dfc.lift.BytecodeLifter;
import dev.denismasterherobrine.generatoraccelerator2.dfc.lift.LiftException;
import dev.denismasterherobrine.generatoraccelerator2.dfc.lift.LiftValidator;
import dev.denismasterherobrine.generatoraccelerator2.dfc.pipeline.CoverageReport;
import dev.denismasterherobrine.generatoraccelerator2.dfc.program.Interpreter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.util.CubicSpline;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Lowers vanilla {@link DensityFunction} trees into {@link Node} IR.
 *
 * <p>Vanilla objects are memoised by identity (so shared holders become shared nodes) and the
 * resulting nodes are interned structurally. Noise instances are collected into a spec table
 * by identity. Unknown classes go through {@link DensityFunctionLowering}, and if nothing
 * claims them they become {@link Node.Unsupported} and are reported.
 */
public final class IrBuilder implements DensityFunctionLowering.Context {
    private final Map<DensityFunction, Node> memo = new IdentityHashMap<>();
    private final Map<Node, Node> interner = new HashMap<>();
    private final List<NoiseSpec> specs = new ArrayList<>();
    private final Map<NormalNoise, Integer> specIndex = new IdentityHashMap<>();
    private final List<BlendedSpec> blended = new ArrayList<>();
    private final Map<BlendedNoise, Integer> blendedIndex = new IdentityHashMap<>();
    private final CoverageReport report;
    private final Deque<String> path = new ArrayDeque<>();
    private String root = "?";

    public IrBuilder(CoverageReport report) {
        this.report = report;
    }

    public List<NoiseSpec> specs() {
        return specs;
    }

    public List<BlendedSpec> blendedSpecs() {
        return blended;
    }

    public int internedNodeCount() {
        return interner.size();
    }

    /** Lowers one router field. {@code rootName} is used in reports only. */
    public Node build(String rootName, DensityFunction fn) {
        this.root = rootName;
        path.clear();
        return lower(fn);
    }

    @Override
    public Node lower(DensityFunction fn) {
        Node cached = memo.get(fn);
        if (cached != null) {
            return cached;
        }
        Node node = intern(lowerNew(fn));
        memo.put(fn, node);
        return node;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <N extends Node> N intern(N node) {
        Node existing = interner.putIfAbsent(node, node);
        return existing == null ? node : (N) existing;
    }

    @Override
    public int noiseSpec(DensityFunction.NoiseHolder holder) {
        NormalNoise noise = holder.noise();
        if (noise == null) {
            throw new IllegalArgumentException("unbound NoiseHolder " + holder.noiseData());
        }
        Integer idx = specIndex.get(noise);
        if (idx == null) {
            idx = specs.size();
            specs.add(NoiseSpec.of(noise));
            specIndex.put(noise, idx);
        }
        return idx;
    }

    private int blendedSpec(BlendedNoise noise) {
        Integer idx = blendedIndex.get(noise);
        if (idx == null) {
            idx = blended.size();
            blended.add(BlendedSpec.of(noise));
            blendedIndex.put(noise, idx);
        }
        return idx;
    }

    private Node child(String edge, DensityFunction fn) {
        path.push(edge);
        try {
            return lower(fn);
        } finally {
            path.pop();
        }
    }

    private Node lowerNew(DensityFunction fn) {
        double min = fn.minValue();
        double max = fn.maxValue();
        if (fn instanceof DensityFunctions.HolderHolder h) {
            return child("holder", h.function().value());
        }
        if (fn instanceof DensityFunctions.MarkerOrMarked m) {
            // Vanilla Marker records and the NoiseChunk wrappers that replace them both implement this.
            Node.MarkerType type = switch (m.type()) {
                case Interpolated -> Node.MarkerType.INTERPOLATED;
                case FlatCache -> Node.MarkerType.FLAT_CACHE;
                case Cache2D -> Node.MarkerType.CACHE_2D;
                case CacheOnce -> Node.MarkerType.CACHE_ONCE;
                case CacheAllInCell -> Node.MarkerType.CACHE_ALL_IN_CELL;
            };
            return new Node.Marker(type, child(m.type().getSerializedName(), m.wrapped()), fn, min, max);
        }
        if (fn instanceof DensityFunctions.Constant c) {
            return new Node.Const(c.value());
        }
        if (fn instanceof DensityFunctions.Ap2 a) {
            Node.Ap2Type type = switch (a.type()) {
                case ADD -> Node.Ap2Type.ADD;
                case MUL -> Node.Ap2Type.MUL;
                case MIN -> Node.Ap2Type.MIN;
                case MAX -> Node.Ap2Type.MAX;
            };
            return new Node.Ap2(type, child("argument1", a.argument1()), child("argument2", a.argument2()), min, max);
        }
        if (fn instanceof DensityFunctions.MulOrAdd m) {
            boolean mul = m.specificType() == DensityFunctions.MulOrAdd.Type.MUL;
            return new Node.MulOrAdd(mul, child("input", m.input()), m.argument(), min, max);
        }
        if (fn instanceof DensityFunctions.Mapped m) {
            Node.MappedType type = switch (m.type()) {
                case ABS -> Node.MappedType.ABS;
                case SQUARE -> Node.MappedType.SQUARE;
                case CUBE -> Node.MappedType.CUBE;
                case HALF_NEGATIVE -> Node.MappedType.HALF_NEGATIVE;
                case QUARTER_NEGATIVE -> Node.MappedType.QUARTER_NEGATIVE;
                case SQUEEZE -> Node.MappedType.SQUEEZE;
            };
            return new Node.Mapped(type, child(m.type().getSerializedName(), m.input()), min, max);
        }
        if (fn instanceof DensityFunctions.Clamp c) {
            return new Node.Clamp(child("clamp", c.input()), c.minValue(), c.maxValue());
        }
        if (fn instanceof DensityFunctions.RangeChoice r) {
            return new Node.RangeChoice(child("input", r.input()), r.minInclusive(), r.maxExclusive(),
                child("when_in_range", r.whenInRange()), child("when_out_of_range", r.whenOutOfRange()), min, max);
        }
        if (fn instanceof DensityFunctions.YClampedGradient y) {
            return new Node.YGradient(y.fromY(), y.toY(), y.fromValue(), y.toValue());
        }
        if (fn instanceof DensityFunctions.Noise n) {
            if (n.noise().noise() == null) {
                return new Node.Const(0.0); // NoiseHolder.getValue returns 0.0 when unbound
            }
            return new Node.Noise(noiseSpec(n.noise()), n.xzScale(), n.yScale(), max);
        }
        if (fn instanceof DensityFunctions.ShiftedNoise s) {
            if (s.noise().noise() == null) {
                return new Node.Const(0.0);
            }
            Node sx = child("shift_x", s.shiftX());
            Node sy = child("shift_y", s.shiftY());
            Node sz = child("shift_z", s.shiftZ());
            return new Node.ShiftedNoise(sx, sy, sz, s.xzScale(), s.yScale(), noiseSpec(s.noise()), max);
        }
        if (fn instanceof DensityFunctions.ShiftA s) {
            return s.offsetNoise().noise() == null ? new Node.Const(0.0) : new Node.ShiftA(noiseSpec(s.offsetNoise()), max);
        }
        if (fn instanceof DensityFunctions.ShiftB s) {
            return s.offsetNoise().noise() == null ? new Node.Const(0.0) : new Node.ShiftB(noiseSpec(s.offsetNoise()), max);
        }
        if (fn instanceof DensityFunctions.Shift s) {
            return s.offsetNoise().noise() == null ? new Node.Const(0.0) : new Node.Shift(noiseSpec(s.offsetNoise()), max);
        }
        if (fn instanceof DensityFunctions.WeirdScaledSampler w) {
            Node input = child("input", w.input());
            boolean type1 = w.rarityValueMapper() == DensityFunctions.WeirdScaledSampler.RarityValueMapper.TYPE1;
            if (w.noise().noise() == null) {
                // r * abs(0.0) == 0.0 for every finite r. Keep the input evaluation for parity of side effects.
                return new Node.Weird(input, -1, type1, max);
            }
            return new Node.Weird(input, noiseSpec(w.noise()), type1, max);
        }
        if (fn instanceof DensityFunctions.Spline s) {
            return new Node.Spline(spline(s.spline()), min, max);
        }
        if (fn instanceof DensityFunctions.BlendDensity b) {
            return new Node.BlendDensity(child("blend_density", b.input()));
        }
        if (fn instanceof DensityFunctions.BlendAlpha) {
            return new Node.BlendAlpha();
        }
        if (fn instanceof DensityFunctions.BlendOffset) {
            return new Node.BlendOffset();
        }
        if (fn instanceof DensityFunctions.BeardifierOrMarker) {
            return new Node.Beardifier(fn, min, max);
        }
        if (fn instanceof DensityFunctions.EndIslandDensityFunction e) {
            return new Node.EndIslands(new EndIslandsSpec(e));
        }
        if (fn instanceof BlendedNoise b) {
            return new Node.Blended(blendedSpec(b), max);
        }
        String reason;
        DensityFunctionLowering lowering = DensityFunctionLowering.find(fn.getClass());
        if (lowering != null) {
            Node lowered = lowering.lower(fn, this);
            if (lowered != null) {
                String err = LiftValidator.validate(fn, lowered, new Interpreter(specs, blended));
                if (err == null) {
                    report.lowered(fn, root, currentPath());
                    return lowered;
                }
                reason = "registered lowering rejected: " + err;
            } else {
                reason = "registered lowering declined";
            }
        } else {
            reason = "no lowering registered";
        }
        if (DfcConfig.bytecodeLifting()) {
            try {
                Node lifted = BytecodeLifter.lift(fn, this);
                String err = LiftValidator.validate(fn, lifted, new Interpreter(specs, blended));
                if (err == null) {
                    report.lifted(fn, root, currentPath());
                    return lifted;
                }
                reason += "; bytecode lift rejected: " + err;
            } catch (LiftException e) {
                reason += "; bytecode lifter: " + e.getMessage();
            } catch (RuntimeException e) {
                reason += "; bytecode lifter crashed: " + e;
            }
        } else {
            reason += "; bytecode lifting disabled";
        }
        report.unsupported(fn, root, currentPath(), reason);
        return new Node.Unsupported(fn, reason, min, max);
    }

    private Node.SplineSpec spline(CubicSpline<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> spline) {
        if (spline instanceof CubicSpline.Constant<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> c) {
            return new Node.SplineConst(c.value());
        }
        CubicSpline.Multipoint<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> m =
            (CubicSpline.Multipoint<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate>) spline;
        Node coordinate = child("spline", m.coordinate().function().value());
        List<Node.SplineSpec> values = new ArrayList<>(m.values().size());
        for (CubicSpline<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> v : m.values()) {
            values.add(spline(v));
        }
        return new Node.SplineMulti(coordinate, m.locations(), values, m.derivatives());
    }

    private String currentPath() {
        if (path.isEmpty()) {
            return "/";
        }
        StringBuilder sb = new StringBuilder();
        var it = path.descendingIterator();
        while (it.hasNext()) {
            sb.append('/').append(it.next());
        }
        return sb.toString();
    }
}
