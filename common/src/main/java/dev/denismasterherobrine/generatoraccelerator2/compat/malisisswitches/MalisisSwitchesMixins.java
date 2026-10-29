package dev.denismasterherobrine.generatoraccelerator2.compat.malisisswitches;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.compat.CodeShape;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;

/** Malisis Switches Reborn mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it. */
public final class MalisisSwitchesMixins {
    private MalisisSwitchesMixins() {}

    private static final Logger LOGGER = Ga2Log.get("Compat");

    public static final String MOD_ID = "malisisswitchesreborn";
    /**
     * {@code StructureTemplate}: at the return of {@code processBlockInfos}, each block whose NBT holds the mod's link data gets that
     * data turned into absolute positions for the placement. Block by block, from the block's own position, NBT and the settings. At
     * the return of {@code placeInWorld}, the placed link sources are refreshed from the template's own palettes. A placement clipped
     * to its box processes fewer blocks, and those it keeps get the same data. GA2 runs the first handler on the clipped result
     * ({@link MalisisTemplates}).
     */
    public static final String TEMPLATE = "dev.mostlyharmless.malisisswitchesreborn.mixin.StructureTemplateMixin";
    public static final String LINKS = "dev.mostlyharmless.malisisswitchesreborn.power.LinkedPowerStructureLinks";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.STRUCTURE_TEMPLATE_CLIP, TEMPLATE, Set.of("restoreStructureLinks", "refreshPlacedLinkedSources"),
            "Linked switches in structure templates still connect. GA2 places only the template blocks inside each chunk and restores "
                + "their links the same way."));

    /** The code the compat was written against (Malisis Switches Reborn 1.0.10 for 26.1.2). */
    private static final String RESTORE = "f74d5512847a0807";
    private static final String REFRESH = "3fe5eae988de1f77";
    private static final String RESTORE_ONE = "e29cc239ea2feb33";

    /** True when the mod's handlers and the per-block restore have the code the compat was written against. Logs what differs. */
    static boolean codeKnown() {
        ClassNode mixin = CodeShape.classNode(TEMPLATE);
        String restore = CodeShape.fingerprint(CodeShape.methodStarting(mixin, "malisisswitchesreborn$restoreStructureLinks"));
        String refresh = CodeShape.fingerprint(CodeShape.methodStarting(mixin, "malisisswitchesreborn$refreshPlacedLinkedSources"));
        String one = CodeShape.fingerprint(CodeShape.method(CodeShape.classNode(LINKS), "restoreForStructurePlacement", null));
        if (!restore.equals(RESTORE) || !refresh.equals(REFRESH) || !one.equals(RESTORE_ONE)) {
            LOGGER.info("{}: unknown code ({}, {}, {}) -> template clipping off", TEMPLATE, restore, refresh, one);
            return false;
        }
        return true;
    }
}
