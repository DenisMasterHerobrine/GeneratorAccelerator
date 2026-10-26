package dev.denismasterherobrine.generatoraccelerator2.mixin.state;

import dev.denismasterherobrine.generatoraccelerator2.state.DirectSets;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Keeps the resource key's bit index for direct holder sets ({@link DirectSets}) on the key itself. */
@Mixin(ResourceKey.class)
public abstract class ResourceKeyIndexMixin implements DirectSets.Indexed {
    /** The index once given, 0 before. */
    @Unique
    private volatile int ga2$directIndex;

    @Override
    public int ga2$directIndex() {
        return ga2$directIndex;
    }

    @Override
    public void ga2$setDirectIndex(int index) {
        this.ga2$directIndex = index;
    }
}
