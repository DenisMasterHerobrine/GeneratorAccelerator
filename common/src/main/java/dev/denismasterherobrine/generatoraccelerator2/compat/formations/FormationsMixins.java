package dev.denismasterherobrine.generatoraccelerator2.compat.formations;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/**
 * Formations mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it.
 *
 * <p>Formations rebuilds a template's block list through its own processors at the head of {@code processBlockInfos}
 * ({@code processBlockInfos}), and turns off kept liquids at the head of {@code placeInWorld} when its waterlogging processor is set
 * ({@code placeInWorld}). With none of its processors, the list comes back as it was. GA2's template clip only clips placements whose
 * processors are all of vanilla's per-block classes, compared by exact class ({@code structure.TemplatePlacement}). A placement with a
 * Formations processor therefore always runs vanilla's call, with Formations' code in it.
 */
public final class FormationsMixins {
    private FormationsMixins() {}

    public static final String MOD_ID = "formations";
    public static final String TEMPLATE = "com.supermartijn642.formations.mixin.StructureTemplateMixin";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.STRUCTURE_TEMPLATE_CLIP, TEMPLATE, Set.of("processBlockInfos", "placeInWorld"),
            "Formations' structure processors still run. GA2 clips only templates without them, where Formations leaves the block list as it is."));
}
