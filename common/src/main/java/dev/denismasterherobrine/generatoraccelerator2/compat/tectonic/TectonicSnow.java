package dev.denismasterherobrine.generatoraccelerator2.compat.tectonic;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.OptionalInt;

/**
 * Tectonic's snow start offset, read as Tectonic's biome mixin reads it ({@code Tectonic.CONFIG.getState().general.snowStartOffset},
 * on every call). Used for the GPU's surface temperature. The value is read when a surface spec is made, and again before each
 * chunk's surface ({@code SurfaceSpec.current}). A changed config leaves the surface to the CPU.
 */
public final class TectonicSnow {
    private TectonicSnow() {}

    private static volatile Object reader;
    private static final Object NONE = new Object();

    private record Reader(Object holder, MethodHandle state, MethodHandle general, MethodHandle offset) {}

    /** The offset, or empty when this version of Tectonic keeps it elsewhere. */
    public static OptionalInt offset() {
        Object r = reader;
        if (r == null) {
            synchronized (TectonicSnow.class) {
                r = reader;
                if (r == null) {
                    reader = r = resolve();
                }
            }
        }
        if (!(r instanceof Reader rd)) {
            return OptionalInt.empty();
        }
        try {
            Object state = rd.state.invoke(rd.holder);
            Object general = rd.general.invoke(state);
            return OptionalInt.of((int) rd.offset.invoke(general));
        } catch (Throwable t) {
            return OptionalInt.empty();
        }
    }

    private static Object resolve() {
        try {
            ClassLoader loader = TectonicSnow.class.getClassLoader();
            Class<?> tectonic = Class.forName("dev.worldgen.tectonic.Tectonic", true, loader);
            Field config = tectonic.getField("CONFIG");
            Object holder = config.get(null);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            // Called through the field's declared public type, as Tectonic's own code calls it.
            MethodHandle state = lookup.unreflect(config.getType().getMethod("getState")).asType(MethodType.methodType(Object.class, Object.class));
            Class<?> configState = Class.forName("dev.worldgen.tectonic.config.ConfigState", true, loader);
            Field generalField = configState.getField("general");
            Field offsetField = generalField.getType().getField("snowStartOffset");
            if (offsetField.getType() != int.class) {
                throw new NoSuchFieldException("snowStartOffset is a " + offsetField.getType());
            }
            MethodHandle general = lookup.unreflectGetter(generalField).asType(MethodType.methodType(Object.class, Object.class));
            MethodHandle offset = lookup.unreflectGetter(offsetField).asType(MethodType.methodType(int.class, Object.class));
            Reader rd = new Reader(holder, state, general, offset);
            // the state must read as the config Tectonic's mixin casts it to
            Object s = state.invoke(holder);
            if (!configState.isInstance(s)) {
                throw new IllegalStateException("its config state is a " + (s == null ? null : s.getClass().getName()));
            }
            return rd;
        } catch (Throwable t) {
            Ga2Log.get("Compat").info("Tectonic snow start offset unreadable ({}) -> GPU surface off",
                t.toString());
            return NONE;
        }
    }

    public static void register() {
        CompatAdapters.register(HookTargets.GPU_SURFACE, TectonicMixins.BIOME, () -> offset().isPresent());
    }

    /**
     * Whether the GPU's surface applies Tectonic's offset. The biome mixin is the only handled change to the temperature methods
     * that changes values. The handled ModernFix and Lithium overwrites return vanilla's values.
     */
    public static boolean applies() {
        return HookTargets.handledMixins(HookTargets.GPU_SURFACE).contains(TectonicMixins.BIOME);
    }
}
