package dev.denismasterherobrine.generatoraccelerator2.api;

import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.Node;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Public extension point: teaches the density function compiler how to lower a
 * non-vanilla {@link DensityFunction} class into compiler IR.
 *
 * <p>The lowering must reproduce the class's {@code compute} bit for bit. Lowered nodes
 * are validated against the original object at compile time. A mismatch rejects the
 * lowering, and the node falls back to calling the object's own {@code compute}. The
 * fallback is reported in the log and in {@code /ga2 dfc report}.
 */
@FunctionalInterface
public interface DensityFunctionLowering {
    /** Services available while lowering. */
    interface Context {
        /** Lowers a child density function (recursively, with the same reporting rules). */
        Node lower(DensityFunction child);

        /** Registers a bound {@link DensityFunction.NoiseHolder} and returns its spec index for {@link Node.Noise} and friends. */
        int noiseSpec(DensityFunction.NoiseHolder holder);

        /** Interns a node so structurally identical subtrees share one instance. */
        <N extends Node> N intern(N node);

        /** Records why the lowering is about to return {@code null}. The coverage report shows it. */
        default void decline(String reason) {}
    }

    /**
     * @return the lowered node, or {@code null} to decline. The compiler then tries bytecode
     *         lifting and finally the per-node fallback.
     */
    Node lower(DensityFunction function, Context context);

    Map<Class<?>, DensityFunctionLowering> REGISTRY = new ConcurrentHashMap<>();

    static void register(Class<? extends DensityFunction> type, DensityFunctionLowering lowering) {
        REGISTRY.put(type, lowering);
    }

    static DensityFunctionLowering find(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            DensityFunctionLowering l = REGISTRY.get(c);
            if (l != null) {
                return l;
            }
        }
        return null;
    }
}
