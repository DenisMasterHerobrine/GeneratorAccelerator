package dev.denismasterherobrine.generatoraccelerator2.mixin.state;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * {@code FluidState.isEmpty}, remembered per state. The fluid's answer is fixed, so every caller costs one field read. Put in
 * place once the class is complete, when no other mod changed the method ({@code compat.LateOverwrites}).
 */
@Mixin(value = FluidState.class, priority = 5000)
public abstract class FluidStateEmptyMixin {
    /** 0 not known yet, 1 empty, 2 not. */
    @Unique
    private byte ga2$empty;
    @Unique
    private static final HookTargets.Hook GA2$HOOK = HookTargets.hook(HookTargets.FLUID_EMPTY);

    /** The body of {@code isEmpty()}: the fluid's answer remembered. */
    @Unique
    private boolean ga2$isEmpty() {
        byte e = this.ga2$empty;
        if (e != 0) {
            return e == 1;
        }
        boolean empty = ((FluidInvoker) ((FluidState) (Object) this).getType()).ga2$isEmpty();
        if ((dev.denismasterherobrine.generatoraccelerator2.state.StateFast.ENABLED && GA2$HOOK.allowed())) {
            this.ga2$empty = empty ? (byte) 1 : (byte) 2;
        }
        return empty;
    }
}
