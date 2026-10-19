package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** GPU decoration ({@code gpu.decor.DecorSpec}) reads this vanilla object's configuration. */
@Mixin(net.minecraft.util.valueproviders.WeightedListInt.class)
public interface WeightedListIntAccessor {
    @Accessor("distribution")
    net.minecraft.util.random.SimpleWeightedRandomList<net.minecraft.util.valueproviders.IntProvider> ga2$list();
}
