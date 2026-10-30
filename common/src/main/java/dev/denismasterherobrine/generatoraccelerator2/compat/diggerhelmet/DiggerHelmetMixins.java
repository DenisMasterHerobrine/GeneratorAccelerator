package dev.denismasterherobrine.generatoraccelerator2.compat.diggerhelmet;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/** Digger Helmet mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it. */
public final class DiggerHelmetMixins {
    private DiggerHelmetMixins() {}

    public static final String MOD_ID = "diggerhelmet";
    /**
     * {@code BlockLightEngine.getEmission}: the helmet's moving light, added only when the game runs as a client. A dedicated server
     * keeps vanilla's emission, which GA2's parallel light reads. In singleplayer the server's engine would see the helmet's light,
     * so the parallel light stays off there ({@code DiggerHelmetLight}).
     */
    public static final String EMISSION = "dev.thomas.diggerhelmet.mixin.BlockLightEngineMixin";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.LIGHT_PARALLEL, EMISSION, Set.of("onGetEmission"),
            "Digger Helmet's light still works for players. On a dedicated server it changes no light, so GA2's faster lighting stays on there."));
}
