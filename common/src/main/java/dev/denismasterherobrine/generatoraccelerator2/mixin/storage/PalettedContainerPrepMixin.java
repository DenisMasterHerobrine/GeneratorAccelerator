package dev.denismasterherobrine.generatoraccelerator2.mixin.storage;

import dev.denismasterherobrine.generatoraccelerator2.storage.SavePrep;
import javax.annotation.Nullable;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** The container copy and tag encoded ahead of the chunk save ({@link SavePrep}). Null when there is none. */
@Mixin(PalettedContainer.class)
public abstract class PalettedContainerPrepMixin implements SavePrep.Slot {
    @Unique
    private volatile SavePrep.Prepared ga2$prepared;

    @Override
    @Nullable
    public SavePrep.Prepared ga2$prepared() {
        return this.ga2$prepared;
    }

    @Override
    public void ga2$setPrepared(@Nullable SavePrep.Prepared prepared) {
        this.ga2$prepared = prepared;
    }
}
