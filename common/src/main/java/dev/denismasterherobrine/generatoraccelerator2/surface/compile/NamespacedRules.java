package dev.denismasterherobrine.generatoraccelerator2.surface.compile;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.slf4j.Logger;

/**
 * TerraBlender's {@code NamespacedSurfaceRuleSource(base, sources)}, the surface rule of every TerraBlender
 * dimension (Biomes O' Plenty, Regions Unexplored and most biome mods). Per block, it applies the rule of the
 * biome's namespace when there is one. It applies {@code base} when that rule gives nothing or the biome's
 * namespace has none.
 *
 * <p>Its rule ({@code NamespacedRule.tryApply}) asks {@code biome.is(key -> sources has key's namespace)} and then
 * runs that namespace's rule, falling back to {@code base} on {@code null}. A biome without a key matches no
 * namespace. Exactly one namespace can match, so this is the vanilla sequence
 * {@code [if biome in namespace n1 then r1, ..., if biome in namespace nk then rk, base]} with the namespaces in the
 * map's order. That is also the order {@code apply} builds the rules in, followed by {@code base}. Lowered that
 * way, the whole tree compiles instead of running as one opaque rule.
 *
 * <p>Recognised by class name and record shape only, so GA2 needs no TerraBlender dependency. Anything else (a
 * different shape in a future version) stays opaque.
 */
final class NamespacedRules {
    private NamespacedRules() {}

    private static final Logger LOGGER = Ga2Log.get("Surface");
    static final String CLASS_NAME = "terrablender.worldgen.surface.NamespacedSurfaceRuleSource";

    /** The parts of a TerraBlender namespaced source. */
    record Parts(SurfaceRules.RuleSource base, Map<String, SurfaceRules.RuleSource> sources) {}

    @Nullable
    static Parts of(SurfaceRules.RuleSource source) {
        Class<?> c = source.getClass();
        if (!c.getName().equals(CLASS_NAME) || !c.isRecord()) {
            return null;
        }
        try {
            RecordComponent[] components = c.getRecordComponents();
            if (components.length != 2 || !components[0].getName().equals("base") || !components[1].getName().equals("sources")
                || !hasNamespacedRule(c)) {
                return null;
            }
            Method base = components[0].getAccessor();
            Method sources = components[1].getAccessor();
            // public accessors of a public record. Opening them only matters if a version makes them non-public
            base.trySetAccessible();
            sources.trySetAccessible();
            Object b = base.invoke(source);
            Object s = sources.invoke(source);
            if (!(b instanceof SurfaceRules.RuleSource baseRule) || !(s instanceof Map<?, ?> map)) {
                return null;
            }
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getKey() instanceof String) || !(e.getValue() instanceof SurfaceRules.RuleSource)) {
                    return null;
                }
            }
            @SuppressWarnings("unchecked")
            Map<String, SurfaceRules.RuleSource> typed = (Map<String, SurfaceRules.RuleSource>) map;
            return new Parts(baseRule, typed);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("TerraBlender namespaced surface rule unreadable -> TerraBlender rule objects", e);
            return null;
        }
    }

    /** The rule TerraBlender builds is a nested record {@code NamespacedRule(context, baseRule, rules)}. */
    private static boolean hasNamespacedRule(Class<?> c) {
        for (Class<?> nested : c.getDeclaredClasses()) {
            if (nested.getSimpleName().equals("NamespacedRule") && nested.isRecord()) {
                RecordComponent[] rc = nested.getRecordComponents();
                return rc.length == 3 && rc[0].getName().equals("context") && rc[1].getName().equals("baseRule") && rc[2].getName().equals("rules");
            }
        }
        return false;
    }
}
