package dev.denismasterherobrine.generatoraccelerator2.compat.xycraft;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.compat.CodeShape;
import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;

/** XyCraft mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it. */
public final class XyCraftMixins {
    private XyCraftMixins() {}

    private static final Logger LOGGER = Ga2Log.get("Compat");

    public static final String MOD_ID = "xycraft_machines";
    /**
     * {@code Biome.shouldFreeze} and {@code shouldSnow}: inside a heater's volume of a server level, the answer is false. A
     * generation region is no server level, and the handlers only ever turn an answer to false. GA2 skips the two tests only for
     * biomes whose answer is false anyway ({@code WarmBiomes}).
     */
    public static final String SHOULD_FREEZE = "tv.soaryn.xycraft.machines.mixins.ShouldFreezeMixin";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, FeatureTargets.compatKey(FeatureTargets.CLIMATE), SHOULD_FREEZE, Set.of("shouldFreeze", "shouldSnow"),
            "XyCraft's heaters still keep water and snow from freezing around them. GA2 only skips the checks where nothing freezes."));

    /** The handlers' code the compat was written against (XyCraft Machines 0.9.2). */
    private static final String FREEZE = "42f6be9fbcf437ed";
    private static final String SNOW = "e659618f55690fa9";

    /** True when the mod's handlers have the code the compat was written against. Logs what differs. */
    public static boolean codeKnown() {
        ClassNode node = CodeShape.classNode(SHOULD_FREEZE);
        String freeze = CodeShape.fingerprint(CodeShape.methodStarting(node, "xycraft$shouldFreeze"));
        String snow = CodeShape.fingerprint(CodeShape.methodStarting(node, "xycraft$shouldSnow"));
        if (!freeze.equals(FREEZE) || !snow.equals(SNOW)) {
            LOGGER.info("{}: unknown code ({}, {}) -> warm biome skip off", SHOULD_FREEZE, freeze, snow);
            return false;
        }
        return true;
    }
}
