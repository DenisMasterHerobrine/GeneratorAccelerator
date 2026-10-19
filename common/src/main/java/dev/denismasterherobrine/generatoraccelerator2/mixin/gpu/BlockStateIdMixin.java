package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import dev.denismasterherobrine.generatoraccelerator2.gpu.decor.Ga2StateId;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** The GPU id of the state ({@code gpu.decoration}). Costs one field read per logged block write instead of a map lookup. */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateIdMixin implements Ga2StateId {
    @Unique
    private volatile Ga2StateId.Cache ga2$stateId;

    @Override
    public Ga2StateId.Cache ga2$stateId() {
        return ga2$stateId;
    }

    @Override
    public void ga2$setStateId(Ga2StateId.Cache c) {
        this.ga2$stateId = c;
    }
}
