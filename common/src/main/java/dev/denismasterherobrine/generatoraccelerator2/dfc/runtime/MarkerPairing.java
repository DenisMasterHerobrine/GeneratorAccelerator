package dev.denismasterherobrine.generatoraccelerator2.dfc.runtime;

import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.util.CubicSpline;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseRouter;

/**
 * Walks a RandomState-level router and the same router after {@code NoiseChunk.wrap} in lock-step.
 * It records which chunk object replaced which RandomState-level object. Cache markers become the
 * chunk's wrapper instances. The beardifier marker becomes the chunk's {@code Beardifier}. Blend
 * alpha/offset become the chunk's blending caches. Non-vanilla nodes map to their re-created
 * instance. The compiled class stores RandomState-level objects as extern templates. This map
 * resolves them for one chunk in a few microseconds.
 */
public final class MarkerPairing {
    private final Map<DensityFunction, DensityFunction> map;
    private final Map<DensityFunction, Boolean> visited;

    /**
     * The largest walks so far (nodes visited, objects replaced), for whole routers and for samplers. A chunk's walk is as large as
     * the last one of the same router, so its tables start at that size instead of growing (and copying) on the way. Racy hints.
     */
    private static int routerNodes = 64;
    private static int routerReplaced = 32;
    private static int samplerNodes = 32;
    private static int samplerReplaced = 16;

    private MarkerPairing(int nodes, int replaced) {
        this.visited = new IdentityHashMap<>(nodes);
        this.map = new IdentityHashMap<>(replaced);
    }

    public static MarkerPairing of(NoiseRouter base, NoiseRouter wrapped) {
        MarkerPairing p = new MarkerPairing(routerNodes, routerReplaced + 2);
        p.pair(base.barrierNoise(), wrapped.barrierNoise());
        p.pair(base.fluidLevelFloodednessNoise(), wrapped.fluidLevelFloodednessNoise());
        p.pair(base.fluidLevelSpreadNoise(), wrapped.fluidLevelSpreadNoise());
        p.pair(base.lavaNoise(), wrapped.lavaNoise());
        p.pair(base.temperature(), wrapped.temperature());
        p.pair(base.vegetation(), wrapped.vegetation());
        p.pair(base.continents(), wrapped.continents());
        p.pair(base.erosion(), wrapped.erosion());
        p.pair(base.depth(), wrapped.depth());
        p.pair(base.ridges(), wrapped.ridges());
        p.pair(base.initialDensityWithoutJaggedness(), wrapped.initialDensityWithoutJaggedness());
        p.pair(base.finalDensity(), wrapped.finalDensity());
        p.pair(base.veinToggle(), wrapped.veinToggle());
        p.pair(base.veinRidged(), wrapped.veinRidged());
        p.pair(base.veinGap(), wrapped.veinGap());
        if (p.visited.size() > routerNodes) {
            routerNodes = p.visited.size();
        }
        if (p.map.size() > routerReplaced) {
            routerReplaced = p.map.size();
        }
        return p;
    }

    /** Pairs the six climate fields of a RandomState-level router with a chunk-wrapped sampler. */
    public static MarkerPairing ofSampler(NoiseRouter base, net.minecraft.world.level.biome.Climate.Sampler sampler) {
        MarkerPairing p = new MarkerPairing(samplerNodes, samplerReplaced);
        p.pair(base.temperature(), sampler.temperature());
        p.pair(base.vegetation(), sampler.humidity());
        p.pair(base.continents(), sampler.continentalness());
        p.pair(base.erosion(), sampler.erosion());
        p.pair(base.depth(), sampler.depth());
        p.pair(base.ridges(), sampler.weirdness());
        if (p.visited.size() > samplerNodes) {
            samplerNodes = p.visited.size();
        }
        if (p.map.size() > samplerReplaced) {
            samplerReplaced = p.map.size();
        }
        return p;
    }

    /** Registers an explicit replacement (used for objects the NoiseChunk constructor wires outside the router). */
    public void put(DensityFunction base, DensityFunction chunk) {
        if (base != chunk) {
            map.put(base, chunk);
        }
    }

    /** @return the chunk-level object for a RandomState-level one, or the input itself when nothing replaced it. */
    public DensityFunction resolve(DensityFunction base) {
        DensityFunction r = map.get(base);
        return r != null ? r : base;
    }

    public DensityFunction lookup(DensityFunction base) {
        return map.get(base);
    }

    private void pair(DensityFunction base, DensityFunction chunk) {
        if (base instanceof DensityFunctions.HolderHolder h) {
            // NoiseChunk.wrapNew unwraps holders, so the chunk side is already the held value.
            pair(h.function().value(), chunk);
            return;
        }
        if (visited.put(base, Boolean.TRUE) != null) {
            return;
        }
        if (base != chunk) {
            map.put(base, chunk);
        }
        if (base instanceof DensityFunctions.MarkerOrMarked bm && chunk instanceof DensityFunctions.MarkerOrMarked cm) {
            pair(bm.wrapped(), cm.wrapped());
        } else if (base instanceof DensityFunctions.Ap2 b && chunk instanceof DensityFunctions.Ap2 c) {
            pair(b.argument1(), c.argument1());
            pair(b.argument2(), c.argument2());
        } else if (base instanceof DensityFunctions.Ap2 b && chunk instanceof DensityFunctions.MulOrAdd c) {
            // JSON-decoded routers keep add/mul(constant, x) as a two-argument node because the constant sits
            // inside a holder. NoiseChunk.wrap unwraps holders, and the factory folds the constant into MulOrAdd(x, c).
            if (unwrap(b.argument1()) instanceof DensityFunctions.Constant) {
                pair(b.argument2(), c.input());
            } else {
                pair(b.argument1(), c.input());
            }
        } else if (base instanceof DensityFunctions.MulOrAdd b && chunk instanceof DensityFunctions.MulOrAdd c) {
            pair(b.input(), c.input());
        } else if (base instanceof DensityFunctions.Mapped b && chunk instanceof DensityFunctions.Mapped c) {
            pair(b.input(), c.input());
        } else if (base instanceof DensityFunctions.Clamp b && chunk instanceof DensityFunctions.Clamp c) {
            pair(b.input(), c.input());
        } else if (base instanceof DensityFunctions.RangeChoice b && chunk instanceof DensityFunctions.RangeChoice c) {
            pair(b.input(), c.input());
            pair(b.whenInRange(), c.whenInRange());
            pair(b.whenOutOfRange(), c.whenOutOfRange());
        } else if (base instanceof DensityFunctions.ShiftedNoise b && chunk instanceof DensityFunctions.ShiftedNoise c) {
            pair(b.shiftX(), c.shiftX());
            pair(b.shiftY(), c.shiftY());
            pair(b.shiftZ(), c.shiftZ());
        } else if (base instanceof DensityFunctions.WeirdScaledSampler b && chunk instanceof DensityFunctions.WeirdScaledSampler c) {
            pair(b.input(), c.input());
        } else if (base instanceof DensityFunctions.BlendDensity b && chunk instanceof DensityFunctions.BlendDensity c) {
            pair(b.input(), c.input());
        } else if (base instanceof DensityFunctions.Spline b && chunk instanceof DensityFunctions.Spline c) {
            pairSpline(b.spline(), c.spline());
        } else if (base.getClass() == chunk.getClass() && base.getClass().isRecord() && base != chunk) {
            // Modded record density functions: mapAll typically rebuilds the record with mapped children.
            for (java.lang.reflect.Method accessor : FUNCTION_COMPONENTS.get(base.getClass())) {
                if (accessor == null) {
                    continue;
                }
                try {
                    Object bv = accessor.invoke(base);
                    Object cv = accessor.invoke(chunk);
                    if (bv instanceof DensityFunction bd && cv instanceof DensityFunction cd) {
                        pair(bd, cd);
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // leave the subtree unpaired. Its externs then resolve to the RandomState-level objects (exact, slower)
                }
            }
        } else if (hasChildren(base)) {
            divergences.add(Names.mojangSimpleName(base.getClass()) + " vs " + Names.mojangSimpleName(chunk.getClass())
                + (chunk instanceof DensityFunctions.MarkerOrMarked mm ? "(" + mm.type() + ")" : ""));
        }
    }

    private final List<String> divergences = new java.util.ArrayList<>();

    /**
     * Per record class, the accessors of its density function components, made accessible once. An entry is null where that
     * failed, and the walk skips it as it skipped a failing component. Cached because every NoiseChunk pairs its router, and each
     * {@code getRecordComponents} and {@code getAccessor} call copied the reflection objects anew.
     */
    private static final ClassValue<java.lang.reflect.Method[]> FUNCTION_COMPONENTS = new ClassValue<>() {
        @Override
        protected java.lang.reflect.Method[] computeValue(Class<?> type) {
            List<java.lang.reflect.Method> accessors = new java.util.ArrayList<>();
            try {
                java.lang.reflect.RecordComponent[] components = type.getRecordComponents();
                if (components == null) {
                    return new java.lang.reflect.Method[0];
                }
                for (java.lang.reflect.RecordComponent rc : components) {
                    if (!DensityFunction.class.isAssignableFrom(rc.getType())) {
                        continue;
                    }
                    java.lang.reflect.Method accessor;
                    try {
                        accessor = rc.getAccessor();
                        accessor.setAccessible(true);
                    } catch (RuntimeException e) {
                        accessor = null;
                    }
                    accessors.add(accessor);
                }
            } catch (LinkageError e) {
                // a component type this side cannot load: the subtree stays unpaired
                return new java.lang.reflect.Method[0];
            }
            return accessors.toArray(new java.lang.reflect.Method[0]);
        }
    };

    /** Places where the two trees had different shapes, for diagnostics. */
    public List<String> divergences() {
        return divergences;
    }

    private static DensityFunction unwrap(DensityFunction f) {
        while (f instanceof DensityFunctions.HolderHolder h) {
            f = h.function().value();
        }
        return f;
    }

    private static boolean hasChildren(DensityFunction f) {
        return f instanceof DensityFunctions.MarkerOrMarked || f instanceof DensityFunctions.Ap2 || f instanceof DensityFunctions.MulOrAdd
            || f instanceof DensityFunctions.Mapped || f instanceof DensityFunctions.Clamp || f instanceof DensityFunctions.RangeChoice
            || f instanceof DensityFunctions.ShiftedNoise || f instanceof DensityFunctions.WeirdScaledSampler
            || f instanceof DensityFunctions.BlendDensity || f instanceof DensityFunctions.Spline;
    }

    private void pairSpline(CubicSpline<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> base,
                            CubicSpline<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> chunk) {
        if (base instanceof CubicSpline.Multipoint<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> b
            && chunk instanceof CubicSpline.Multipoint<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> c) {
            pair(b.coordinate().function().value(), c.coordinate().function().value());
            List<CubicSpline<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate>> bv = b.values();
            List<CubicSpline<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate>> cv = c.values();
            for (int i = 0; i < bv.size() && i < cv.size(); i++) {
                pairSpline(bv.get(i), cv.get(i));
            }
        }
    }
}
