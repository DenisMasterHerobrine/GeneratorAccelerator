package dev.denismasterherobrine.generatoraccelerator2.compat.diggerhelmet;

import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.Reflect;

/** Keeps GA2's parallel light next to Digger Helmet on a dedicated server only ({@link DiggerHelmetMixins#EMISSION}). */
public final class DiggerHelmetLight {
    private DiggerHelmetLight() {}

    public static void register() {
        // the client's main class is missing from a dedicated server. Digger Helmet's handler acts only in a client game.
        boolean dedicated = Reflect.type("net.minecraft.client.Minecraft") == null;
        CompatAdapters.register(HookTargets.LIGHT_PARALLEL, DiggerHelmetMixins.EMISSION, () -> dedicated);
    }
}
