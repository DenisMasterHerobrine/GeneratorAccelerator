package dev.denismasterherobrine.generatoraccelerator2.compat;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;

/**
 * Vanilla classes that other mods' mixin configs target, as reported to
 * {@code DfcMixinPlugin.acceptTargets} once every config is prepared. By then each config's own plugin
 * has filtered that config's mixins. The aquifer and beardifier fast paths take over a whole method at
 * the method's head, so another mod's injection or redirect inside that method would be skipped. These
 * fast paths stay off for any class listed here unless forced with a system property.
 *
 * <p>Loaded by the mixin plugin before any game class, so it must not reference Minecraft types.
 */
public final class MixinTargets {
    public static final String AQUIFER = "net.minecraft.world.level.levelgen.Aquifer$NoiseBasedAquifer";
    public static final String BEARDIFIER = "net.minecraft.world.level.levelgen.Beardifier";
    public static final String AQUIFER_FORCE = "generatoraccelerator2.aquifer.force";
    public static final String BEARDIFIER_FORCE = "generatoraccelerator2.beardifier.force";
    public static final String SURFACE_LEVEL_FORCE = "generatoraccelerator2.surfacelevel.force";

    private static final Logger LOGGER = Ga2Log.get("Compat");

    private static volatile boolean recorded;
    private static volatile boolean aquiferTargeted;
    private static volatile boolean beardifierTargeted;
    private static volatile boolean surfaceLevelInspected;
    private static volatile boolean surfaceLevelChanged;
    private static volatile boolean warnedUnrecorded;

    private MixinTargets() {}

    /** Class-level record of other configs' targets. Informational only, because the fast paths decide per method. */
    public static void record(Set<String> otherTargets) {
        aquiferTargeted |= otherTargets.contains(Names.runtimeBinaryClass(AQUIFER));
        beardifierTargeted |= otherTargets.contains(Names.runtimeBinaryClass(BEARDIFIER));
        recorded = true;
    }

    /** False until the mixin plugin has reported the other configs' targets. */
    public static boolean recorded() {
        return recorded;
    }

    private static volatile boolean aquiferInspected;
    private static volatile List<String> aquiferForeign = List.of();
    private static volatile boolean beardifierInspected;
    private static volatile List<String> beardifierForeign = List.of();

    /** Foreign mixins found in the aquifer methods the fast path takes over, just before the fast path's late mixin applied. */
    public static void recordAquifer(List<String> foreignMixins) {
        recordAquifer(foreignMixins, List.of());
    }

    private static volatile List<String> aquiferOrderKept = List.of();

    /**
     * {@link #recordAquifer(List)}, plus the mixins whose changes ({@code KnownMixins}, key {@code aquifer.orderKept}) leave every
     * status vanilla's as long as statuses are computed in vanilla's order. With such mixins, the fast path only searches.
     */
    public static void recordAquifer(List<String> foreignMixins, List<String> orderKept) {
        recordAquifer(foreignMixins, orderKept, List.of());
    }

    private static volatile List<String> aquiferLiquidRegions = List.of();

    /**
     * {@link #recordAquifer(List, List)}, plus the mixins whose step GA2 runs itself on its own answers ({@code KnownMixins}, key
     * {@code aquifer.liquidRegions}: Better Caves' liquid regions, {@code aquifer.LiquidRegions}). They leave the fast path and
     * the GPU on.
     */
    public static void recordAquifer(List<String> foreignMixins, List<String> orderKept, List<String> liquidRegions) {
        aquiferForeign = List.copyOf(foreignMixins);
        aquiferOrderKept = List.copyOf(orderKept);
        aquiferLiquidRegions = List.copyOf(liquidRegions);
        aquiferInspected = true;
        warnForeign("aquifer fast path", foreignMixins, AQUIFER_FORCE);
        if (foreignMixins.isEmpty() && !orderKept.isEmpty()) {
            LOGGER.info("aquifer shared with {} -> fast path kept, statuses in vanilla order (search only)",
                orderKept);
        }
        if (!liquidRegions.isEmpty()) {
            LOGGER.info("aquifer shared with {} -> fast path and GPU kept, the liquid regions applied to their answers", liquidRegions);
        }
    }

    /** True when Better Caves' liquid regions change the aquifer's answers, which GA2's own answers then go through. */
    public static boolean aquiferLiquidRegions() {
        return !aquiferLiquidRegions.isEmpty();
    }

    /** True when the aquifer fast path may only search ({@link #recordAquifer(List, List)}). */
    public static boolean aquiferSearchOnly() {
        return !aquiferOrderKept.isEmpty() && !Boolean.getBoolean(AQUIFER_FORCE);
    }

    /** True when the aquifer is vanilla's own, as the GPU computes it (no other mod's change, not even an order-kept one). */
    public static boolean aquiferVanilla() {
        return aquiferAllowed() && (aquiferOrderKept.isEmpty() || Boolean.getBoolean(AQUIFER_FORCE));
    }

    /** Every other mod's change to the aquifer methods, the order-kept ones included (for the GPU's status line). */
    public static List<String> aquiferChanges() {
        List<String> all = new java.util.ArrayList<>(aquiferForeign);
        all.addAll(aquiferOrderKept);
        all.addAll(aquiferLiquidRegions);
        return all;
    }

    /**
     * Other mods' changes to the beardifier methods that the piece index takes over and cannot run itself. Two kinds of
     * change are not included ({@code BeardifierChain}): the steps after vanilla's sum, which the piece index runs, and the
     * wrappers around the method.
     */
    public static void recordBeardifier(List<String> foreignMixins) {
        beardifierForeign = List.copyOf(foreignMixins);
        beardifierInspected = true;
        warnForeign("beardifier piece index", foreignMixins, BEARDIFIER_FORCE);
    }

    private static volatile boolean beardCullingInspected;
    private static volatile List<String> beardCullingForeign = List.of();

    /**
     * Any other mod's change to the beardifier's density. For example, YUNG's API adds its own pieces at the end of
     * {@code compute}. With such a change, the noise programs ask the beardifier for every cell instead of skipping the
     * cells that no vanilla piece reaches.
     */
    public static void recordBeardCulling(List<String> foreignMixins) {
        beardCullingForeign = List.copyOf(foreignMixins);
        beardCullingInspected = true;
        if (!foreignMixins.isEmpty()) {
            LOGGER.info("beardifier density shared with {} -> queried per cell by noise programs", foreignMixins);
        }
    }

    /** The mixins of other mods that change the beardifier's {@code compute} (empty when it is vanilla's). */
    public static List<String> beardCullingForeign() {
        return beardCullingForeign;
    }

    /** True when the beardifier's {@code compute} is vanilla's, so cells outside every vanilla piece's reach get exactly 0. */
    public static boolean beardCullingAllowed() {
        return beardCullingInspected && beardCullingForeign.isEmpty();
    }

    public static List<String> aquiferForeign() {
        return aquiferForeign;
    }

    public static List<String> beardifierForeign() {
        return beardifierForeign;
    }

    private static void warnForeign(String feature, List<String> foreign, String forceProperty) {
        if (foreign.isEmpty()) {
            return;
        }
        if (Boolean.getBoolean(forceProperty)) {
            LOGGER.warn("{} methods conflict with {} -> kept (forced: -D{}=true)", feature, foreign, forceProperty);
        } else {
            LOGGER.warn("{} methods conflict with {} -> disabled (force: -D{}=true)",
                feature, foreign, forceProperty);
        }
    }

    public static boolean aquiferAllowed() {
        return allowed(aquiferInspected, !aquiferForeign.isEmpty(), AQUIFER_FORCE);
    }

    public static boolean beardifierAllowed() {
        return allowed(beardifierInspected, !beardifierForeign.isEmpty(), BEARDIFIER_FORCE);
    }

    /** Foreign mixins found in NoiseChunk's surface level methods just before this mod's hook was applied. */
    public static void recordSurfaceLevel(List<String> foreignMixins) {
        surfaceLevelChanged |= !foreignMixins.isEmpty();
        surfaceLevelInspected = true;
    }

    /** NoiseChunk is checked per method (see the plugin's postApply), not per class. Almost every worldgen mod patches NoiseChunk somewhere. */
    public static boolean surfaceLevelAllowed() {
        if (Boolean.getBoolean(SURFACE_LEVEL_FORCE)) {
            return true;
        }
        return surfaceLevelInspected && !surfaceLevelChanged;
    }

    /**
     * If the plugin never reported, nothing is known about other mods, so the fast paths stay off.
     * The plugin always reports when this mod's mixin config loads.
     */
    private static boolean allowed(boolean inspected, boolean changedElsewhere, String forceProperty) {
        if (Boolean.getBoolean(forceProperty)) {
            return true;
        }
        if (!inspected) {
            if (!warnedUnrecorded) {
                warnedUnrecorded = true;
                LOGGER.warn("aquifer/beardifier methods not inspected by mixin plugin -> fast paths off");
            }
            return false;
        }
        return !changedElsewhere;
    }
}
