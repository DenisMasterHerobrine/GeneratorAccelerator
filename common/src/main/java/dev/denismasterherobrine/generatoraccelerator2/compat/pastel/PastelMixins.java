package dev.denismasterherobrine.generatoraccelerator2.compat.pastel;

import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/** Pastel mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it. */
public final class PastelMixins {
    private PastelMixins() {}

    public static final String MOD_ID = "pastel";
    /**
     * At the tail of {@code GeodeFeature.place}, after a geode is placed in full. Places gemstone ores of the geode's inner block
     * around the geode, written through the level. The ores draw from the feature's random after the geode's own draws.
     */
    public static final String GEODE_ORES = "earth.terrarium.pastel.mixin.GeodesGenerateWithGemstoneOresMixin";

    /**
     * At the head of {@code ServerLevel.onBlockStateChange}: a game event for the changed block, only on the server thread
     * ({@code server.isSameThread()}). On worldgen's threads the handler does nothing. The mixin's other handler changes
     * {@code tick}.
     */
    public static final String SERVER_LEVEL = "earth.terrarium.pastel.mixin.ServerLevelMixin";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.STATE_POI_SKIP, SERVER_LEVEL,
            Set.of("emitBlockChangedEvent", "sleepThroughDay"),
            "Pastel's block-change events still fire on the main thread. GA2 skips them on generation threads, where Pastel ignores them anyway."),
        new KnownMixins.Entry(MOD_ID, FeatureTargets.compatKey("geode"), GEODE_ORES, Set.of("generate"),
            "Pastel's gemstone ores around geodes still generate. GA2's faster geodes place them at the same point vanilla does."));
}
