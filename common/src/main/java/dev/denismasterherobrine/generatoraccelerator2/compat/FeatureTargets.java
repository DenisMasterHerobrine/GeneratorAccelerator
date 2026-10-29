package dev.denismasterherobrine.generatoraccelerator2.compat;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;

/**
 * Per-method stand-down record for the feature-stage hooks. The mixin plugin inspects each hooked
 * vanilla method just before this mod's late-priority mixin is applied. The plugin records the
 * foreign mixins that changed the method. The matching fast path then stays off unless forced with
 * {@code -Dgeneratoraccelerator2.features.<name>.force=true}. Loaded by the mixin plugin, so it must
 * not reference Minecraft types.
 */
public final class FeatureTargets {
    private static final Logger LOGGER = Ga2Log.get("Compat");
    private static final Map<String, List<String>> FOREIGN = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> INSPECTED = new ConcurrentHashMap<>();
    /** Answers of {@link #allowed} once the hook was inspected. Hot paths ask on every call. */
    private static final Map<String, Boolean> ALLOWED = new ConcurrentHashMap<>();
    /** The {@code .force} property per hook, read once (system properties are set at launch). */
    private static final Map<String, Boolean> FORCED = new ConcurrentHashMap<>();
    /** Handles of hooks asked on hot paths ({@link #hook}). */
    private static final Map<String, Hook> HOOKS = new ConcurrentHashMap<>();

    private FeatureTargets() {}

    /**
     * A hook asked on a hot path (per placement, per modifier). {@link #allowed} answers as {@link FeatureTargets#allowed}
     * does. Once the answer is known, it comes from a field instead of a lookup by name on every call. Recording the hook
     * again forgets the handle's answer, as it forgets the answer kept by {@link FeatureTargets#allowed}.
     */
    public static final class Hook {
        private final String name;
        /** 0 not known yet, 1 yes, 2 no. */
        private volatile byte allowed;

        private Hook(String name) {
            this.name = name;
        }

        public boolean allowed() {
            byte a = this.allowed;
            if (a != 0) {
                return a == 1;
            }
            Boolean known = ALLOWED.get(this.name);
            if (known != null) {
                this.allowed = known ? (byte) 1 : (byte) 2;
                return known;
            }
            // computes, keeps and hands this handle the answer once the hook was inspected
            return FeatureTargets.allowed(this.name);
        }
    }

    /** The hot-path handle of {@code hook}, one per name. */
    public static Hook hook(String hook) {
        return HOOKS.computeIfAbsent(hook, Hook::new);
    }

    private static boolean forced(String hook) {
        Boolean forced = FORCED.get(hook);
        if (forced == null) {
            forced = Boolean.getBoolean(forceProperty(hook));
            FORCED.put(hook, forced);
        }
        return forced;
    }

    /** Hook names, also the config and force-property keys. */
    public static final String REGION = "regionCache";
    public static final String PLACEMENT = "placementPipeline";
    public static final String DECORATION = "decoration";
    /** {@code PlacementFilter.getPositions}: other filters are asked {@code shouldPlace} directly. */
    public static final String PLACEMENT_FILTERS = "placementFilters";
    /** {@code RepeatingPlacement.getPositions}: other repeats are asked {@code count} directly. */
    public static final String PLACEMENT_REPEATS = "placementRepeats";
    /** {@code WorldGenRegion.getBlockState / getFluidState} and {@code ProtoChunk}'s, read in place ({@code RegionReads}). */
    public static final String REGION_READS = "regionReads";
    /** {@code CountOnEveryLayerPlacement}'s layer search, transcribed by the placement pipeline. */
    public static final String PLACEMENT_LAYERS = "placementLayers";
    /** The {@code Feature} helpers the fast paths do the work of ({@code safeSetBlock}, {@code isAdjacentToAir}, ...). */
    public static final String FEATURE_HELPERS = "featureHelpers";
    /** {@code WorldGenRegion.getBlockState} changed by no other mod at all (callbacks included), so reading the region directly skips nothing. */
    public static final String REGION_READS_UNCHANGED = "regionReadsUnchanged";
    /** The predicate calls of {@code BlockPredicateFilter.shouldPlace} and {@code EnvironmentScanPlacement.getPositions}, asked through the predicate programs. */
    public static final String PREDICATE_CALLS = "predicateCalls";
    /** The climate methods behind {@code shouldFreeze} and {@code shouldSnow}: biomes too warm for both skip them ({@code WarmBiomes}). */
    public static final String CLIMATE = "climate";

    /** The key of a vanilla placement modifier class the pipeline transcribes, by internal name ({@code placement.BiomeFilter}). */
    public static String modifierKey(String internalName) {
        String name = Names.mojangClass(internalName);
        return "placement." + name.substring(name.lastIndexOf('/') + 1);
    }

    /** Key of a feature hook in {@link KnownMixins} and {@link CompatAdapters}. */
    public static String compatKey(String hook) {
        return "features." + hook;
    }

    private static final Map<String, List<String>> HANDLED = new ConcurrentHashMap<>();

    /** Records an inspection. GA2's code handles changes of mixins that {@link KnownMixins} covers. Other mixins make the hook stand down. */
    public static void recordChanges(String hook, List<MergedMethods.Change> changes) {
        Map<String, List<String>> byMixin = new LinkedHashMap<>();
        for (MergedMethods.Change c : changes) {
            byMixin.computeIfAbsent(c.mixin(), k -> new ArrayList<>()).add(c.method().substring(0, c.method().indexOf('(')));
        }
        List<String> foreign = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : byMixin.entrySet()) {
            KnownMixins.Entry known = KnownMixins.find(compatKey(hook), e.getKey(), e.getValue());
            if (known == null) {
                foreign.add(e.getKey());
                continue;
            }
            HANDLED.merge(hook, List.of(e.getKey()), (a, b) -> Stream.concat(a.stream(), b.stream()).distinct().toList());
            LOGGER.info("feature hook '{}' shared with {} ({}) -> kept, emulated: {}",
                hook, e.getKey(), known.modId(), known.what());
        }
        record(hook, foreign);
    }

    public static List<String> handledMixins(String hook) {
        return HANDLED.getOrDefault(hook, Collections.emptyList());
    }

    public static Map<String, List<String>> allHandled() {
        return Collections.unmodifiableMap(HANDLED);
    }

    public static void record(String hook, List<String> foreignMixins) {
        if (!foreignMixins.isEmpty()) {
            FOREIGN.merge(hook, List.copyOf(foreignMixins), (a, b) -> Stream.concat(a.stream(), b.stream()).distinct().toList());
            if (forced(hook)) {
                LOGGER.warn("feature hook '{}' conflicts with {} -> kept (forced: -D{}=true)", hook, foreignMixins, forceProperty(hook));
            } else {
                LOGGER.warn("feature hook '{}' conflicts with {} -> disabled (force: -D{}=true)",
                    hook, foreignMixins, forceProperty(hook));
            }
        }
        // the answers kept so far are forgotten once the findings they come from are in
        INSPECTED.put(hook, Boolean.TRUE);
        ALLOWED.remove(hook);
        Hook handle = HOOKS.get(hook);
        if (handle != null) {
            handle.allowed = 0;
        }
    }

    public static String forceProperty(String hook) {
        return "generatoraccelerator2.features." + hook + ".force";
    }

    /**
     * True when the hook is forced on. Otherwise true when the hook was inspected, no foreign mixin touched the hook's target method,
     * and the compat code of every handled mixin is set up ({@link CompatAdapters#ready}).
     */
    public static boolean allowed(String hook) {
        Boolean known = ALLOWED.get(hook);
        if (known != null) {
            return known;
        }
        boolean forced = forced(hook);
        if (!forced && !INSPECTED.containsKey(hook)) {
            return false;
        }
        boolean allowed = forced || !FOREIGN.containsKey(hook) && CompatAdapters.ready(compatKey(hook), handledMixins(hook));
        ALLOWED.put(hook, allowed);
        Hook handle = HOOKS.get(hook);
        if (handle != null) {
            handle.allowed = allowed ? (byte) 1 : (byte) 2;
        }
        return allowed;
    }

    public static boolean inspected(String hook) {
        return INSPECTED.containsKey(hook);
    }

    public static List<String> foreign(String hook) {
        return FOREIGN.getOrDefault(hook, Collections.emptyList());
    }

    public static Map<String, List<String>> allForeign() {
        return Collections.unmodifiableMap(FOREIGN);
    }
}
