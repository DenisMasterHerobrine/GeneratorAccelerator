package dev.denismasterherobrine.generatoraccelerator2.features;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.config.ConfigSpec;
import dev.denismasterherobrine.generatoraccelerator2.dfc.Compat;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;

/**
 * Decides, per hook, whether a feature-stage fast path may run. Inputs: the config switch, the
 * per-method foreign-mixin record and the known chunk-system mods. The region cache argument does not
 * cover those mods' chunk access semantics.
 */
public final class FeaturesCompat {
    private FeaturesCompat() {}

    private static final Logger LOGGER = Ga2Log.get("Features");
    /**
     * Mods whose chunk systems GA2 keeps. GA2 takes over their worldgen mixins, not their schedulers. The
     * region cache's argument about chunk access does not cover these mods, so the region cache stands down.
     */
    private static final Set<String> CHUNK_SYSTEM_MODS = Set.of("c2me");

    private static volatile String chunkSystemMod;
    private static volatile boolean chunkSystemChecked;

    private static String chunkSystemMod() {
        if (!chunkSystemChecked) {
            String found = null;
            for (String id : CHUNK_SYSTEM_MODS) {
                if (Compat.isLoaded(id)) {
                    found = id;
                    break;
                }
            }
            chunkSystemMod = found;
            chunkSystemChecked = true;
            if (found != null) {
                LOGGER.warn("'{}' loaded -> region chunk cache off", found);
            }
        }
        return chunkSystemMod;
    }

    // asked per region, per placed feature, per placement and per section read. Answered from fields once known.
    private static final FeatureTargets.Hook REGION = FeatureTargets.hook(FeatureTargets.REGION);
    private static final FeatureTargets.Hook REGION_READS = FeatureTargets.hook(FeatureTargets.REGION_READS);
    private static final FeatureTargets.Hook PLACEMENT_LAYERS = FeatureTargets.hook(FeatureTargets.PLACEMENT_LAYERS);
    private static final FeatureTargets.Hook PLACEMENT = FeatureTargets.hook(FeatureTargets.PLACEMENT);
    private static final FeatureTargets.Hook FEATURE_HELPERS = FeatureTargets.hook(FeatureTargets.FEATURE_HELPERS);
    private static final FeatureTargets.Hook DECORATION = FeatureTargets.hook(FeatureTargets.DECORATION);
    private static final FeatureTargets.Hook TREES = FeatureTargets.hook("trees");

    public static boolean regionCacheAllowed() {
        return FeaturesConfig.regionCache() && REGION.allowed()
            && (chunkSystemMod() == null || Boolean.getBoolean(FeatureTargets.forceProperty(FeatureTargets.REGION)));
    }

    /** The region's reads in place ({@code RegionReads}). Only with the region cache, because the reads use its chunk grid. */
    public static boolean regionReadsAllowed() {
        return FeaturesConfig.regionReads() && regionCacheAllowed() && REGION_READS.allowed();
    }

    /** {@code CountOnEveryLayerPlacement} transcribed by the placement pipeline. */
    public static boolean placementLayersAllowed() {
        return PLACEMENT_LAYERS.allowed();
    }

    public static boolean placementPipelineAllowed() {
        return FeaturesConfig.placementPipeline() && PLACEMENT.allowed();
    }

    /** A feature fast path: config switch for {@code name} and no foreign change of the hooked method. */
    public static boolean fastPathAllowed(String name) {
        // the fast paths also do the work of Feature's helpers (safeSetBlock, isAdjacentToAir, ...).
        // A change to those helpers counts too.
        return FeaturesConfig.fastPath(name) && FeatureTargets.allowed(name) && FEATURE_HELPERS.allowed();
    }

    /**
     * {@link #fastPathAllowed} for one fast path, asked on every placement of its feature. The config switch and hook are found
     * once instead of by name on each call.
     */
    public static final class FastPath {
        private final ConfigSpec.BooleanValue config;
        private final FeatureTargets.Hook hook;

        private FastPath(String name) {
            this.config = FeaturesConfig.FAST_PATHS.get(name);
            this.hook = FeatureTargets.hook(name);
        }

        public boolean allowed() {
            return (config == null || FeaturesConfig.on(config)) && hook.allowed() && FEATURE_HELPERS.allowed();
        }
    }

    public static final FastPath ORE = new FastPath("ore"), GEODE = new FastPath("geode"), DISK = new FastPath("disk"), SPRING = new FastPath("spring"),
        SNOW_AND_FREEZE = new FastPath("snowAndFreeze"), SEAGRASS = new FastPath("seagrass"), KELP = new FastPath("kelp"),
        MULTIFACE = new FastPath("multiface"), MONSTER_ROOM = new FastPath("monsterRoom"), SIMPLE_BLOCK = new FastPath("simpleBlock"),
        SCULK = new FastPath("sculk");

    public static boolean decorationAllowed() {
        return FeaturesConfig.decorationSetup() && DECORATION.allowed();
    }

    public static boolean treesAllowed() {
        return FeaturesConfig.trees() && TREES.allowed();
    }

    /** Human-readable status lines for {@code /ga2 features report}. */
    public static List<String> report() {
        List<String> out = new ArrayList<>();
        out.add(line(FeatureTargets.REGION, regionCacheAllowed(), FeaturesConfig.regionCache(), chunkSystemMod()));
        out.add(line(FeatureTargets.REGION_READS, regionReadsAllowed(), FeaturesConfig.regionReads(), null));
        out.add(line(FeatureTargets.PLACEMENT, placementPipelineAllowed(), FeaturesConfig.placementPipeline(), null));
        out.add(line(FeatureTargets.PLACEMENT_LAYERS, placementLayersAllowed(), FeaturesConfig.placementPipeline(), null));
        out.add(line(FeatureTargets.FEATURE_HELPERS, FeatureTargets.allowed(FeatureTargets.FEATURE_HELPERS), true, null));
        out.add(line(FeatureTargets.PLACEMENT_FILTERS, FeatureTargets.allowed(FeatureTargets.PLACEMENT_FILTERS), FeaturesConfig.placementPipeline(), null));
        out.add(line(FeatureTargets.PLACEMENT_REPEATS, FeatureTargets.allowed(FeatureTargets.PLACEMENT_REPEATS), FeaturesConfig.placementPipeline(), null));
        out.add(line("trees", treesAllowed(), FeaturesConfig.trees(), null));
        out.add(line(FeatureTargets.DECORATION, decorationAllowed(), FeaturesConfig.decorationSetup(), null));
        for (String name : FeaturesConfig.FAST_PATH_NAMES) {
            out.add(line(name, fastPathAllowed(name), FeaturesConfig.fastPath(name), null));
        }
        return out;
    }

    private static String line(String hook, boolean active, boolean configOn, String knownMod) {
        StringBuilder sb = new StringBuilder(hook).append(": ").append(active ? "on" : "off");
        if (!configOn) {
            sb.append(" (config)");
        } else if (knownMod != null && !Boolean.getBoolean(FeatureTargets.forceProperty(hook))) {
            sb.append(" (mod '").append(knownMod).append("' present)");
        } else if (!FeatureTargets.inspected(hook)) {
            sb.append(" (hook not applied)");
        } else if (!FeatureTargets.foreign(hook).isEmpty()) {
            sb.append(" (method also changed by ").append(FeatureTargets.foreign(hook)).append(")");
        }
        return sb.toString();
    }
}
