package dev.denismasterherobrine.generatoraccelerator2.features;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counters for the feature-stage fast paths. Counters for the cheap per-chunk / per-feature events are
 * always maintained. They feed {@code /ga2 features stats}. The per-block counters are compiled out
 * unless {@code -Dgeneratoraccelerator2.features.stats=true} is set at startup.
 */
public final class FeatureStats {
    private FeatureStats() {}

    public static final boolean DETAILED = Boolean.getBoolean("generatoraccelerator2.features.stats");

    public static final LongAdder REGIONS = new LongAdder();
    public static final LongAdder CHUNK_CACHE_HITS = new LongAdder();
    public static final LongAdder CHUNK_CACHE_MISSES = new LongAdder();
    public static final LongAdder PLACEMENTS_FAST = new LongAdder();
    public static final LongAdder PLACEMENTS_GENERIC_MODIFIERS = new LongAdder();
    /** Modifiers of other classes run as filters ({@code shouldPlace}) or repeats ({@code count}) without their streams. */
    public static final LongAdder PLACEMENTS_GENERIC_FILTERS = new LongAdder();
    public static final LongAdder PLACEMENTS_GENERIC_REPEATS = new LongAdder();
    public static final LongAdder WRITES_FAST = new LongAdder();
    public static final LongAdder WRITES_DELEGATED = new LongAdder();
    public static final LongAdder BIOME_MEMO_HITS = new LongAdder();
    /** Biome checks answered by the region's biome set (every biome in it has the feature). */
    public static final LongAdder BIOME_SET_HITS = new LongAdder();
    /** Regions that answered a biome lookup from their zoom cache. */
    public static final LongAdder REGION_ZOOMS = new LongAdder();
    public static final LongAdder REGION_BIOME_LOOKUPS = new LongAdder();
    /** Sculk patches left to vanilla's code: another mod's sculk behaviour block may be within their reach ({@code SculkFast}). */
    public static final LongAdder SCULK_OTHERS_NEAR = new LongAdder();

    /**
     * Per feature class: fast-path placements and fallbacks to vanilla. Each class's counter is found once through a
     * {@link ClassValue} (a placement used to hash the class name into the map every time).
     */
    private static final Map<String, LongAdder> FAST = new ConcurrentHashMap<>();
    private static final Map<String, LongAdder> FALLBACK = new ConcurrentHashMap<>();
    private static final Map<String, LongAdder> GENERIC = new ConcurrentHashMap<>();
    private static final ClassValue<LongAdder> FAST_BY_CLASS = counters(FAST);
    private static final ClassValue<LongAdder> FALLBACK_BY_CLASS = counters(FALLBACK);
    private static final ClassValue<LongAdder> GENERIC_BY_CLASS = counters(GENERIC);

    private static ClassValue<LongAdder> counters(Map<String, LongAdder> byName) {
        return new ClassValue<>() {
            @Override
            protected LongAdder computeValue(Class<?> type) {
                return byName.computeIfAbsent(type.getSimpleName(), k -> new LongAdder());
            }
        };
    }

    public static void fast(Class<?> feature) {
        FAST_BY_CLASS.get(feature).increment();
    }

    public static void fallback(Class<?> feature) {
        FALLBACK_BY_CLASS.get(feature).increment();
    }

    public static void genericModifier(Class<?> modifier) {
        GENERIC_BY_CLASS.get(modifier).increment();
    }

    public static Map<String, LongAdder> genericModifiers() {
        return GENERIC;
    }

    public static Map<String, LongAdder> fastByFeature() {
        return FAST;
    }

    public static Map<String, LongAdder> fallbackByFeature() {
        return FALLBACK;
    }

    public static void reset() {
        for (LongAdder a : new LongAdder[] { REGIONS, CHUNK_CACHE_HITS, CHUNK_CACHE_MISSES, PLACEMENTS_FAST, PLACEMENTS_GENERIC_MODIFIERS, PLACEMENTS_GENERIC_FILTERS, PLACEMENTS_GENERIC_REPEATS,
            WRITES_FAST, WRITES_DELEGATED, BIOME_MEMO_HITS, BIOME_SET_HITS }) {
            a.reset();
        }
        // the class values keep their counters, so the counters are zeroed rather than dropped
        FAST.values().forEach(LongAdder::reset);
        FALLBACK.values().forEach(LongAdder::reset);
    }
}
