package dev.denismasterherobrine.generatoraccelerator2.compat.adastra;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/** Ad Astra mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it. */
public final class AdAstraMixins {
    private AdAstraMixins() {}

    public static final String MOD_ID = "ad_astra";
    /**
     * {@code FlowingFluid}: in a place without air, water forms no new source ({@code getNewLiquid}), and in low gravity it does not
     * spread ({@code spread}). GA2 leaves out the post-processing tick of a water source with water sources on its four sides and
     * below ({@code fluid.StillWater}). That tick asks {@code getNewLiquid} only for the block below, which has water above it. The
     * answer is water either way, which cannot replace the source below. The spread finds nowhere to go, cancelled or not.
     */
    public static final String FLUIDS = "earth.terrarium.adastra.mixins.common.environment.FlowingFluidMixin";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.FLUID_POST_PROCESS, FLUIDS, Set.of("getNewLiquid", "spread"),
            "Ad Astra's water still behaves differently without air or gravity. The water GA2 leaves alone cannot change either way."));
}
