package dev.denismasterherobrine.generatoraccelerator2.features.modded;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;
import sun.misc.Unsafe;

/**
 * Per-thread copies of the features whose placements keep their state in the feature's own fields and assign those fields anew at
 * the start of each placement ({@link FeatureSafetyScan#perPlacementState}). A feature is one registered object, so such placements
 * used to run one at a time. Each worker thread places on its own copy instead: a copy has every field of the object (made without
 * running a constructor), and the placement replaces the per-placement ones before it reads them.
 */
public final class FeatureCopies {
    private FeatureCopies() {}

    /** Copies made: one per thread and feature object. */
    public static final LongAdder MADE = new LongAdder();
    /** This thread's copies by feature object. A copy maps to itself. */
    private static final ThreadLocal<IdentityHashMap<Object, Object>> COPIES = ThreadLocal.withInitial(IdentityHashMap::new);
    /** Classes whose objects could not be copied: they keep the shared lock. */
    private static final Map<Class<?>, Boolean> FAILED = new ConcurrentHashMap<>();
    @Nullable
    private static final Unsafe UNSAFE = unsafe();

    /** This thread's copy of {@code feature} (the object itself when it is a copy), or null when it cannot be copied. */
    @Nullable
    public static Object forThread(Object feature) {
        IdentityHashMap<Object, Object> copies = COPIES.get();
        Object copy = copies.get(feature);
        if (copy == null) {
            copy = copy(feature);
            if (copy == null) {
                return null;
            }
            copies.put(feature, copy);
            copies.put(copy, copy);
            MADE.increment();
        }
        return copy;
    }

    @Nullable
    private static Object copy(Object o) {
        Class<?> type = o.getClass();
        if (UNSAFE == null || FAILED.containsKey(type)) {
            return null;
        }
        try {
            Object copy = UNSAFE.allocateInstance(type);
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers())) {
                        f.setAccessible(true);
                        f.set(copy, f.get(o));
                    }
                }
            }
            return copy;
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (FAILED.putIfAbsent(type, Boolean.TRUE) == null) {
                Ga2Log.get("Features").warn("feature {} -> no per-thread copies, placed one at a time ({})", type.getName(), e.toString());
            }
            return null;
        }
    }

    @Nullable
    private static Unsafe unsafe() {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (Unsafe) f.get(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
