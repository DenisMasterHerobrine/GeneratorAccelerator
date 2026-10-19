package dev.denismasterherobrine.generatoraccelerator2.mixin.state;

import dev.denismasterherobrine.generatoraccelerator2.state.PoiStates;
import dev.denismasterherobrine.generatoraccelerator2.state.StateFlags;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Caches a block state's section-write flags ({@link StateFlags}), registry id ({@code StateIndex}) and whether the state
 * has a POI type ({@link PoiStates}). Each value is computed once.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateFlagsMixin implements StateFlags.Holder, PoiStates.Holder {
    @Unique
    private byte ga2$flags;
    @Unique
    private int ga2$poiStamp;
    @Unique
    private int ga2$registryId;

    @Override
    public int ga2$registryId() {
        return ga2$registryId;
    }

    @Override
    public void ga2$setRegistryId(int id) {
        this.ga2$registryId = id;
    }

    @Override
    public int ga2$poiStamp() {
        return ga2$poiStamp;
    }

    @Override
    public void ga2$setPoiStamp(int stamp) {
        this.ga2$poiStamp = stamp;
    }

    @Override
    public byte ga2$flags() {
        return ga2$flags;
    }

    @Override
    public void ga2$setFlags(byte flags) {
        this.ga2$flags = flags;
    }
}
