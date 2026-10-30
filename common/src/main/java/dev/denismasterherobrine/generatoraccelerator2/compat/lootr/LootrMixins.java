package dev.denismasterherobrine.generatoraccelerator2.compat.lootr;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/** Lootr mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it. */
public final class LootrMixins {
    private LootrMixins() {}

    public static final String MOD_ID = "lootr";
    /**
     * {@code PoiTypes.forState}: Lootr's barrel has the fisherman's POI type, like a vanilla barrel. GA2's per-state answer comes
     * from {@code forState} itself, Lootr's handler included, so the barrel keeps the call.
     */
    public static final String POI_TYPES = "noobanidus.mods.lootr.mixins.MixinPoiTypes";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.STATE_POI_SKIP, POI_TYPES, Set.of("LootrForState"),
            "Lootr barrels still work for fishermen. GA2 asks for each block's POI type with Lootr's code."));
}
