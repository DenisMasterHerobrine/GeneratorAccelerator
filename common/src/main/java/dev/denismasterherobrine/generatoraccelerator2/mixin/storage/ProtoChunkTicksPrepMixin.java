package dev.denismasterherobrine.generatoraccelerator2.mixin.storage;

import dev.denismasterherobrine.generatoraccelerator2.storage.SavePrep;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.world.ticks.ProtoChunkTicks;
import net.minecraft.world.ticks.SavedTick;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** A generating chunk's block or fluid ticks encoded ahead of its save ({@link SavePrep}). {@code save} writes the list in order. */
@Mixin(ProtoChunkTicks.class)
public abstract class ProtoChunkTicksPrepMixin<T> implements SavePrep.TickSlot {
    @Shadow @Final private List<SavedTick<T>> ticks;

    @Unique
    private volatile SavePrep.PreparedTicks ga2$preparedTicks;

    @Override
    @Nullable
    public SavePrep.PreparedTicks ga2$preparedTicks() {
        return this.ga2$preparedTicks;
    }

    @Override
    public void ga2$setPreparedTicks(@Nullable SavePrep.PreparedTicks prepared) {
        this.ga2$preparedTicks = prepared;
    }

    @Override
    public Object[] ga2$tickSnapshot() {
        return this.ticks.toArray();
    }

    @Override
    public boolean ga2$sameTicks(Object[] snapshot) {
        List<SavedTick<T>> list = this.ticks;
        int n = list.size();
        if (n != snapshot.length) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (list.get(i) != snapshot[i]) {
                return false;
            }
        }
        return true;
    }
}
