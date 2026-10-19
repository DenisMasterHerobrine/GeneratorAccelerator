package dev.denismasterherobrine.generatoraccelerator2.compat.citadel;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/** Citadel mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it. */
public final class CitadelMixins {
    private CitadelMixins() {}

    public static final String MOD_ID = "citadel";
    /**
     * Modifies the value of {@code NoiseGeneratorSettings.surfaceRule()} in {@code NoiseBasedChunkGenerator.buildSurface} and
     * {@code applyCarvers}. It merges in the rules registered with Citadel's {@code SurfaceRulesManager} (Alex's Mobs, Alex's
     * Caves). GA2's surface receives the merged rule from {@code buildSurface}. GA2's carver runner asks the same handler for the
     * rule from which it builds the carving context.
     */
    public static final String GENERATOR = "com.github.alexthe666.citadel.mixin.NoiseBasedChunkGeneratorMixin";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.CARVER_ENTRY, GENERATOR, Set.of("citadel$mergeSurfaceRules"),
            "Surface rules added through Citadel (Alex's Mobs, Alex's Caves) still apply. GA2's cave carving uses them too."));
}
