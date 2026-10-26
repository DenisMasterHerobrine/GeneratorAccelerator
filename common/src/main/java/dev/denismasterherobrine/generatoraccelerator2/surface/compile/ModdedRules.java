package dev.denismasterherobrine.generatoraccelerator2.surface.compile;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.tags.TagKey;
import net.minecraft.util.InclusiveRange;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.SurfaceRules;

/**
 * Rule sources of common worldgen libraries whose rules are vanilla rules put together differently. They lower to
 * the vanilla IR and compile with the rest, instead of running as opaque rule objects. Opaque objects would also keep
 * the deep plan from folding anything below them. Recognised by class name and record shape only. GA2 has no
 * dependency on these mods, and a different shape in another version stays opaque.
 *
 * <ul>
 * <li>Lithostitched's {@code TransientMergedRule(rules, original)}. Its surface rule modifiers merge rules into a
 * dimension's rules this way. Applies as vanilla's sequence of {@code rules}. {@code original} is only kept for saving.</li>
 * <li>Blueprint's {@code TransientMergedRuleSource(sequence, original)}: the same, for Team Abnormals' mods.</li>
 * <li>Lithostitched's {@code ReferenceRule(rules)}: the referenced rules in order. The first block wins. If none gives
 * a block, the result is nothing.</li>
 * <li>Regions Unexplored's {@code ConfigRuleSource(key, onEnabled, onDisabled)}: one of two rules by a config switch,
 * read when the rules are applied to a chunk. Lowered to the rule the switch selects now, with a guard the surface
 * builder checks for every chunk. Vanilla builds the chunk if the switch changed since.</li>
 * <li>Lithostitched's {@code AllOfCondition(conditions)} / {@code AnyOfCondition(conditions)}: its conditions applied in
 * order, then asked in order up to the first false (all-of) or true (any-of) one, nothing cached.</li>
 * <li>Lithostitched's {@code BiomeCondition(biomes)}: {@code biomes.contains(biome)} for the block's biome, the same biome
 * vanilla's biome condition reads.</li>
 * <li>Lithostitched's {@code SlopeCondition(threshold)}: vanilla's steepness test generalised (the spread of the four
 * neighbouring surface heights in a range), evaluated lazily per column like it.</li>
 * </ul>
 */
final class ModdedRules {
    private ModdedRules() {}

    static final String LITHO_MERGED = "dev.worldgen.lithostitched.impl.worldgen.surface.rule.TransientMergedRule";
    static final String LITHO_REFERENCE = "dev.worldgen.lithostitched.impl.worldgen.surface.rule.ReferenceRule";
    static final String LITHO_ALL_OF = "dev.worldgen.lithostitched.impl.worldgen.surface.condition.AllOfCondition";
    static final String LITHO_ANY_OF = "dev.worldgen.lithostitched.impl.worldgen.surface.condition.AnyOfCondition";
    static final String LITHO_BIOME = "dev.worldgen.lithostitched.impl.worldgen.surface.condition.BiomeCondition";
    static final String LITHO_SLOPE = "dev.worldgen.lithostitched.impl.worldgen.surface.condition.SlopeCondition";
    static final String RU_CONFIG = "net.regions_unexplored.worldgen.rulesource.ConfigRuleSource";
    /**
     * Blueprint's {@code TransientMergedRuleSource(sequence, original)}. Team Abnormals' mods merge their surface rules into a
     * dimension's rules this way. One rule is that rule. More rules form a sequence ({@code SequenceRule}, the first block wins),
     * with vanilla's sequence semantics. {@code original} is only kept for saving.
     */
    static final String BLUEPRINT_MERGED = "com.teamabnormals.blueprint.core.registry.BlueprintSurfaceRules$TransientMergedRuleSource";
    static final String ELYSIUM_BIOME_TAG = "net.jadenxgamer.elysium_api.api.surface_rules.condition.BiomeTagConditionSource";
    private static final String RU_CONFIG_HANDLER = "net.regions_unexplored.config.RUConfigHandler";

    /** Lithostitched's or Blueprint's merged rules, or null when {@code source} is not one. */
    @Nullable
    static List<SurfaceRules.RuleSource> merged(SurfaceRules.RuleSource source) {
        Object[] c = components(source, LITHO_MERGED, "rules", "original");
        if (c == null) {
            c = components(source, BLUEPRINT_MERGED, "sequence", "original");
        }
        return c != null && c[0] instanceof List<?> list ? ruleSources(list) : null;
    }

    /** Lithostitched's referenced rules, or null when {@code source} is not a reference rule. */
    @Nullable
    static List<SurfaceRules.RuleSource> references(SurfaceRules.RuleSource source) {
        Object[] c = components(source, LITHO_REFERENCE, "rules");
        if (c == null || !(c[0] instanceof HolderSet<?> set)) {
            return null;
        }
        List<Object> values = new ArrayList<>(set.size());
        for (Holder<?> holder : set) {
            values.add(holder.value());
        }
        return ruleSources(values);
    }

    /** Lithostitched's all-of parts, or null when {@code source} is not an all-of condition. */
    @Nullable
    static List<SurfaceRules.ConditionSource> allOf(SurfaceRules.ConditionSource source) {
        Object[] c = components(source, LITHO_ALL_OF, "conditions");
        return c != null && c[0] instanceof List<?> list ? conditionSources(list) : null;
    }

    /** Lithostitched's any-of parts, or null when {@code source} is not an any-of condition. */
    @Nullable
    static List<SurfaceRules.ConditionSource> anyOf(SurfaceRules.ConditionSource source) {
        Object[] c = components(source, LITHO_ANY_OF, "conditions");
        return c != null && c[0] instanceof List<?> list ? conditionSources(list) : null;
    }

    /** The biomes of Lithostitched's biome condition, or null when {@code source} is not one. */
    @Nullable
    @SuppressWarnings("unchecked")
    static HolderSet<Biome> biomes(SurfaceRules.ConditionSource source) {
        Object[] c = components(source, LITHO_BIOME, "biomes");
        return c != null && c[0] instanceof HolderSet<?> set ? (HolderSet<Biome>) set : null;
    }

    /**
     * The tag of Elysium API's {@code BiomeTagConditionSource(biomeTag)} (Jaden's Nether Expansion's surface rules), or null when
     * {@code source} is not one. The condition tests the block's biome against the tag, read lazily per y as vanilla's biome
     * condition reads it.
     */
    @Nullable
    @SuppressWarnings("unchecked")
    static TagKey<Biome> biomeTag(SurfaceRules.ConditionSource source) {
        Object[] c = components(source, ELYSIUM_BIOME_TAG, "biomeTag");
        return c != null && c[0] instanceof TagKey<?> tag ? (TagKey<Biome>) tag : null;
    }

    /** The height difference range {@code {min, max}} of Lithostitched's slope condition, or null when {@code source} is not one. */
    @Nullable
    static int[] slope(SurfaceRules.ConditionSource source) {
        Object[] c = components(source, LITHO_SLOPE, "threshold");
        if (c != null && c[0] instanceof InclusiveRange<?> range && range.minInclusive() instanceof Integer min
            && range.maxInclusive() instanceof Integer max) {
            return new int[] { min, max };
        }
        return null;
    }

    /** The rule a config switch selects now, and a test that it still would. */
    record Config(SurfaceRules.RuleSource selected, BooleanSupplier unchanged) {}

    /** Regions Unexplored's config switch, or null when {@code source} is not one (or its config cannot be read). */
    @Nullable
    static Config config(SurfaceRules.RuleSource source) {
        Object[] c = components(source, RU_CONFIG, "key", "onEnabled", "onDisabled");
        if (c == null || !(c[0] instanceof String key) || !(c[1] instanceof SurfaceRules.RuleSource on) || !(c[2] instanceof SurfaceRules.RuleSource off)) {
            return null;
        }
        try {
            Class<?> handler = Class.forName(RU_CONFIG_HANDLER, false, source.getClass().getClassLoader());
            Field common = handler.getField("COMMON");
            Method test = common.getType().getMethod("test", String.class);
            if (test.getReturnType() != boolean.class) {
                return null;
            }
            boolean enabled = (Boolean) test.invoke(common.get(null), key);
            BooleanSupplier unchanged = () -> {
                try {
                    return (Boolean) test.invoke(common.get(null), key) == enabled;
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return false;
                }
            };
            return new Config(enabled ? on : off, unchanged);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** The record components of {@code source} when it is the record {@code className} with exactly these components. */
    @Nullable
    private static Object[] components(Object source, String className, String... names) {
        Class<?> c = source.getClass();
        if (!c.getName().equals(className) || !c.isRecord()) {
            return null;
        }
        RecordComponent[] rc = c.getRecordComponents();
        if (rc.length != names.length) {
            return null;
        }
        Object[] values = new Object[names.length];
        try {
            for (int i = 0; i < names.length; i++) {
                if (!rc[i].getName().equals(names[i])) {
                    return null;
                }
                Method accessor = rc[i].getAccessor();
                accessor.trySetAccessible();
                values[i] = accessor.invoke(source);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
        return values;
    }

    @Nullable
    private static List<SurfaceRules.RuleSource> ruleSources(List<?> list) {
        List<SurfaceRules.RuleSource> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (!(o instanceof SurfaceRules.RuleSource r)) {
                return null;
            }
            out.add(r);
        }
        return out;
    }

    @Nullable
    private static List<SurfaceRules.ConditionSource> conditionSources(List<?> list) {
        List<SurfaceRules.ConditionSource> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (!(o instanceof SurfaceRules.ConditionSource c)) {
                return null;
            }
            out.add(c);
        }
        return out;
    }
}
