package dev.denismasterherobrine.generatoraccelerator2.compat.naturespirit;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/**
 * Nature's Spirit mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin
 * plugin reads it.
 */
public final class NaturesSpiritMixins {
    private NaturesSpiritMixins() {}

    public static final String MOD_ID = "natures_spirit";
    /** Adds the sugi forest and stratified desert pillars at the top of every column ({@code injectPillars}). */
    public static final String SURFACE = "net.hibiscus.naturespirit.mixin.SurfaceBuilderMixin";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.SURFACE_BUILD, SURFACE, Set.of("injectPillars"),
            "Nature's Spirit's pillars still generate. GA2 runs them inside its own faster surface step."));
}
