package dev.denismasterherobrine.generatoraccelerator2.logging;

import net.minecraft.core.Holder;

/** Registry names of holders for messages, as later versions' {@code Holder.getRegisteredName} gives them. */
public final class HolderNames {
    private HolderNames() {}

    public static String name(Holder<?> holder) {
        return holder.unwrapKey().map(key -> key.location().toString()).orElse("[unregistered]");
    }
}
