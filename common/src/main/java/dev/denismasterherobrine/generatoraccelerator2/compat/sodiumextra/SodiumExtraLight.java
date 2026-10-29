package dev.denismasterherobrine.generatoraccelerator2.compat.sodiumextra;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.compat.Reflect;
import dev.denismasterherobrine.generatoraccelerator2.light.LightLayers;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;

/**
 * Sodium Extra's "light updates" video option, read on every light update. While the player has it off, GA2's light layers record
 * nothing, so Sodium Extra's handlers act on the update as they do without GA2 ({@link SodiumExtraMixins#LIGHT_ENGINE}). Read in
 * Sodium Extra 0.9.3: {@code SodiumExtraClientMod.options().renderSettings.lightUpdates}.
 */
public final class SodiumExtraLight {
    private SodiumExtraLight() {}

    private static final String CLIENT_MOD = "me.flashyreese.mods.sodiumextra.client.SodiumExtraClientMod";

    private static MethodHandle options;
    private static Field renderSettings;
    private static Field lightUpdates;

    /** Finds the option and has GA2's layers check it. False when Sodium Extra's code is not as read: the hook then stays off. */
    public static synchronized boolean register() {
        if (options != null) {
            return true;
        }
        try {
            Class<?> mod = Reflect.type(CLIENT_MOD);
            if (mod == null) {
                return false;
            }
            MethodHandle get = MethodHandles.publicLookup().findStatic(mod, "options", MethodType.methodType(mod.getMethod("options").getReturnType()));
            Field render = get.type().returnType().getField("renderSettings");
            Field updates = render.getType().getField("lightUpdates");
            if (updates.getType() != boolean.class) {
                return false;
            }
            renderSettings = render;
            lightUpdates = updates;
            options = get;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Ga2Log.get("Compat").info("Sodium Extra light updates: unknown shape ({}) -> light layers off", e.toString());
            return false;
        }
        LightLayers.recordWhile(() -> !updatesOff());
        return true;
    }

    /** True while the player has light updates turned off. False when the option cannot be read. */
    static boolean updatesOff() {
        try {
            Object o = options.invoke();
            Object render = o == null ? null : renderSettings.get(o);
            return render != null && !lightUpdates.getBoolean(render);
        } catch (Throwable t) {
            return false;
        }
    }
}
