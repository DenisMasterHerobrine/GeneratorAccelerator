package dev.denismasterherobrine.generatoraccelerator2.compat;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import com.llamalad7.mixinextras.utils.MixinInternals;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;

/**
 * Runs the mixin plugin's inspection of a class once every mixin is fully in place. Mixin calls the config plugins'
 * {@code postApply} from inside Mixin's apply step. MixinExtras adds the calls of its late injectors ({@code @WrapOperation},
 * {@code @WrapWithCondition}, {@code @ModifyExpressionValue}, {@code @WrapMethod}) only afterwards, in MixinExtras' transformer
 * extensions. Those extensions run in the order they were registered. MixinExtras registers them from the plugin of its
 * own mixin config. On Forge that plugin may load after this mod's config. This extension then moves itself behind the
 * MixinExtras extensions before the first class is transformed, and again if another MixinExtras copy registers later.
 * This extension therefore sees the late injector calls too. If this extension cannot be registered, the plugin inspects
 * in {@code postApply} as before and misses those late injectors.
 *
 * <p>Loaded by the mixin plugin, so it must not reference Minecraft types.
 */
public final class LateInspection implements IExtension {
    private static final Logger LOGGER = Ga2Log.get("Compat");
    /** Target class internal name to the GA2 mixins applied to it, waiting for the extension pass. */
    private static final Map<String, List<String>> PENDING = new ConcurrentHashMap<>();
    private static volatile BiConsumer<ClassNode, String> inspector;
    private static volatile boolean registered;

    private LateInspection() {}

    /** Registers the extension. Returns false when that is not possible, and the caller then inspects right away instead. */
    public static synchronized boolean register(BiConsumer<ClassNode, String> inspect) {
        if (registered) {
            return true;
        }
        inspector = inspect;
        try {
            MixinInternals.registerExtension(new LateInspection());
            registered = true;
        } catch (Throwable t) {
            LOGGER.warn("late mixin inspection: registration failed -> MixinExtras late injectors not inspected", t);
        }
        return registered;
    }

    public static boolean registered() {
        return registered;
    }

    /** Queues one GA2 mixin of a class for inspection after the class is complete. */
    public static void queue(String targetClassName, String mixinClassName) {
        PENDING.computeIfAbsent(targetClassName.replace('.', '/'), k -> new CopyOnWriteArrayList<>()).add(mixinClassName);
    }

    @Override
    public boolean checkActive(MixinEnvironment environment) {
        return true;
    }

    @Override
    public void preApply(ITargetClassContext context) {
        keepBehindMixinExtras();
    }

    /**
     * Moves this extension behind every MixinExtras extension. MixinExtras' late injectors are applied in the
     * {@code postApply} of those extensions. Runs before each class is transformed. By then every mixin config's plugin has
     * registered its extensions. The active list is a short immutable copy, so the check costs a few comparisons.
     * Re-registering replaces that copy. The pass in progress keeps iterating the old copy.
     */
    private void keepBehindMixinExtras() {
        List<IExtension> active;
        try {
            active = MixinInternals.getExtensions().getActiveExtensions();
        } catch (Throwable t) {
            return;
        }
        int self = active.indexOf(this);
        if (self < 0) {
            return;
        }
        for (int i = self + 1; i < active.size(); i++) {
            if (active.get(i).getClass().getName().contains("mixinextras")) {
                try {
                    MixinInternals.unregisterExtension(this);
                    MixinInternals.registerExtension(this);
                    LOGGER.debug("late mixin inspection moved after {}", active.get(i).getClass().getName());
                } catch (Throwable t) {
                    LOGGER.warn("late mixin inspection: reorder after MixinExtras failed -> late injectors may be missed", t);
                }
                return;
            }
        }
    }

    @Override
    public void postApply(ITargetClassContext context) {
        ClassNode node = context.getClassNode();
        List<String> mixins = PENDING.remove(node.name);
        BiConsumer<ClassNode, String> inspect = inspector;
        if (mixins == null || inspect == null) {
            return;
        }
        for (String mixin : mixins) {
            inspect.accept(node, mixin);
        }
    }

    @Override
    public void export(MixinEnvironment env, String name, boolean force, ClassNode classNode) {}
}
