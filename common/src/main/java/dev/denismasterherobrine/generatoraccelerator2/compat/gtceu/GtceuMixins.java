package dev.denismasterherobrine.generatoraccelerator2.compat.gtceu;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/** GregTech CEu mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it. */
public final class GtceuMixins {
    private GtceuMixins() {}

    public static final String MOD_ID = "gtceu";
    /**
     * {@code OreVeinifier.create}: with GregTech's {@code removeVanillaLargeOreVeins} on, the handler returns a vein rule that never places
     * a vein. With it off, the handler does nothing. GA2 fills the terrain without veins in the first case ({@link GtceuVeins}) and with
     * vanilla's veins in the second.
     */
    public static final String ORE_VEINS = "com.gregtechceu.gtceu.core.mixins.OreVeinifierMixin";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.NOISE_VEIN_RULE, ORE_VEINS, Set.of("create"),
            "GregTech's setting for vanilla's large ore veins still applies. GA2 leaves the veins out when GregTech removes them."));
}
