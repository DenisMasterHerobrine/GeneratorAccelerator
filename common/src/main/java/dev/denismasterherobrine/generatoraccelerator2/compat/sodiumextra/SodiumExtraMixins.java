package dev.denismasterherobrine.generatoraccelerator2.compat.sodiumextra;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/** Sodium Extra mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it. */
public final class SodiumExtraMixins {
    private SodiumExtraMixins() {}

    public static final String MOD_ID = "sodium_extra";
    /**
     * {@code LevelLightEngine.checkBlock} and {@code runLightUpdates}: with the "light updates" option off, the first is cancelled
     * and the second answers 0, on every light engine (the integrated server's too). With it on, both do nothing. GA2 records no
     * update while it is off ({@link SodiumExtraLight}).
     */
    public static final String LIGHT_ENGINE = "me.flashyreese.mods.sodiumextra.mixin.light_updates.MixinLevelLightEngine";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.LIGHT_LAYERS, LIGHT_ENGINE, Set.of("checkBlock", "doLightUpdates"),
            "Sodium Extra's light updates option still applies. While it is off, GA2 leaves each light update to Sodium Extra."));
}
