package dev.denismasterherobrine.generatoraccelerator2.dfc.runtime;

import java.util.IdentityHashMap;
import java.util.function.Supplier;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Records what each shared piece of the noise router mapped to while a NoiseChunk maps the router through its
 * {@code wrap} visitor. A piece reached again is then not walked, rebuilt and hashed again. Examples are a
 * registry holder used by several router fields, and the coordinate of every nested spline point.
 *
 * <p>Exact: {@code wrap} is memoised by equality. Mapping the same source object a second time builds an equal
 * tree, and {@code wrap} answers every node of that tree with the instance it stored the first time. So the
 * result is the object the first mapping returned. The second walk creates or registers nothing new
 * (interpolators, caches). The memo belongs to one NoiseChunk and one thread.
 */
public final class MapAllMemo {
    private MapAllMemo() {}

    private static final ThreadLocal<IdentityHashMap<Object, DensityFunction>> CURRENT = new ThreadLocal<>();
    private static volatile boolean enabled = !Boolean.getBoolean("generatoraccelerator2.mapAllMemo.off");

    /** For the exactness test: maps every piece again, as vanilla does. */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /** The memo of the NoiseChunk mapping on this thread, or {@code null}. */
    public static IdentityHashMap<Object, DensityFunction> current() {
        return CURRENT.get();
    }

    public static <T> T with(IdentityHashMap<Object, DensityFunction> memo, Supplier<T> body) {
        if (!enabled) {
            return body.get();
        }
        IdentityHashMap<Object, DensityFunction> previous = CURRENT.get();
        CURRENT.set(memo);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** {@code mapped(source)} through the current memo, keyed by {@code key}'s identity. */
    public static DensityFunction lookup(Object key, Supplier<DensityFunction> mapped) {
        IdentityHashMap<Object, DensityFunction> memo = CURRENT.get();
        if (memo == null) {
            return mapped.get();
        }
        DensityFunction result = memo.get(key);
        if (result == null) {
            result = mapped.get();
            memo.put(key, result);
        }
        return result;
    }
}
