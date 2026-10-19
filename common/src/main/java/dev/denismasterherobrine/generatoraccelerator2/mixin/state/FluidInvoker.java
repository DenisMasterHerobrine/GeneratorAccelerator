package dev.denismasterherobrine.generatoraccelerator2.mixin.state;

import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The fluid's own {@code isEmpty} (protected), for {@link FluidStateEmptyMixin}. */
@Mixin(Fluid.class)
public interface FluidInvoker {
    @Invoker("isEmpty")
    boolean ga2$isEmpty();
}
