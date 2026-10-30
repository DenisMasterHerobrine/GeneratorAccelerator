package dev.denismasterherobrine.generatoraccelerator2.compat.tfc;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/**
 * TerraFirmaCraft mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it.
 * TFC's climate and fluid tick mixins are not here on purpose. They change what the world does, so those hooks stay off.
 */
public final class TfcMixins {
    private TfcMixins() {}

    public static final String MOD_ID = "tfc";
    /**
     * {@code LiquidBlockRenderer.isNeighborSameFluid} and {@code getHeight}: a fluid also counts as the same as every fluid it can
     * mix with. That only hides more faces. Water with vanilla water blocks on all six sides still shows none, so GA2 can leave it out.
     */
    public static final String LIQUID_RENDERER = "net.dries007.tfc.mixin.client.LiquidBlockRendererMixin";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.FLUID_HIDDEN_WATER, LIQUID_RENDERER,
            Set.of("isNeighborSameFluidWithMixing", "getWaterHeightWithMixing"),
            "TFC's mixing fluids still join without a seam. GA2 leaves out only water hidden by water on all sides."));
}
