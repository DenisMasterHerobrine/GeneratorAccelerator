package dev.denismasterherobrine.generatoraccelerator2.compat;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;

/**
 * The runtime side of {@link KnownMixins}. Each {@code compat/<modid>} module registers one check per hook and handled
 * mixin. The check confirms that the module's code for that mixin is set up: handler methods found, the mod's classes and
 * registry entries resolved. A hook with a handled mixin stays on only while that check passes. Checks run once, on first
 * use (worldgen time).
 *
 * <p>Read by {@link HookTargets}, so it must not reference Minecraft types.
 */
public final class CompatAdapters {
    private CompatAdapters() {}

    private static final Logger LOGGER = Ga2Log.get("Compat");
    private static final Map<String, BooleanSupplier> CHECKS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> RESULTS = new ConcurrentHashMap<>();

    public static void register(String hook, String mixin, BooleanSupplier ready) {
        CHECKS.put(hook + "|" + mixin, ready);
    }

    /** True when every one of {@code mixins} (handled for {@code hook}) has a registered check that passes. */
    public static boolean ready(String hook, List<String> mixins) {
        for (String mixin : mixins) {
            if (!ready(hook, mixin)) {
                return false;
            }
        }
        return true;
    }

    public static boolean ready(String hook, String mixin) {
        String key = hook + "|" + mixin;
        Boolean known = RESULTS.get(key);
        if (known != null) {
            return known;
        }
        // once per key, even when worker threads ask at the same time (a check may register blocks or hooks)
        synchronized (CompatAdapters.class) {
            known = RESULTS.get(key);
            if (known != null) {
                return known;
            }
            boolean ok = check(hook, mixin, key);
            RESULTS.put(key, ok);
            return ok;
        }
    }

    private static boolean check(String hook, String mixin, String key) {
        BooleanSupplier check = CHECKS.get(key);
        boolean ok;
        if (check == null) {
            ok = false;
            LOGGER.warn("hook '{}': no adapter for {} -> disabled", hook, mixin);
        } else {
            try {
                ok = check.getAsBoolean();
            } catch (RuntimeException | LinkageError e) {
                ok = false;
                LOGGER.warn("hook '{}': adapter for {} failed to init -> disabled", hook, mixin, e);
            }
            if (!ok) {
                LOGGER.warn("hook '{}': adapter for {} incompatible with this mod version -> disabled",
                    hook, mixin);
            }
        }
        return ok;
    }
}
