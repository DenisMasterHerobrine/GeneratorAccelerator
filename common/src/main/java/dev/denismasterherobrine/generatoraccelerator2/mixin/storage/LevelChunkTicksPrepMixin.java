package dev.denismasterherobrine.generatoraccelerator2.mixin.storage;

import dev.denismasterherobrine.generatoraccelerator2.storage.SavePrep;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import javax.annotation.Nullable;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.SavedTick;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * A full chunk's block or fluid ticks encoded ahead of its save ({@link SavePrep}). {@code save} writes the pending ticks
 * (loaded, not yet scheduled), then the queue in iteration order. The snapshot and the comparison use the same order. When
 * another mod keeps the ticks in its own storage instead of the queue (Lithium's tick scheduler, whose save writes them in the order
 * {@code getAll} gives), both read {@code getAll}.
 */
@Mixin(LevelChunkTicks.class)
public abstract class LevelChunkTicksPrepMixin<T> implements SavePrep.TickSlot {
    @Shadow @Final private Queue<ScheduledTick<T>> tickQueue;
    @Shadow @Nullable private List<SavedTick<T>> pendingTicks;

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
        List<SavedTick<T>> pending = this.pendingTicks;
        int p = pending == null ? 0 : pending.size();
        Object[] queued = this.tickQueue == null ? ga2$all() : null;
        Object[] out = new Object[p + (queued != null ? queued.length : this.tickQueue.size())];
        int i = 0;
        for (int k = 0; k < p; k++) {
            out[i++] = pending.get(k);
        }
        if (queued != null) {
            for (Object tick : queued) {
                if (tick == null) {
                    throw new IllegalStateException("ticks changed while copied");
                }
                out[i++] = tick;
            }
        } else {
            for (ScheduledTick<T> tick : this.tickQueue) {
                out[i++] = tick;
            }
        }
        if (i != out.length) {
            throw new IllegalStateException("ticks changed while copied");
        }
        return out;
    }

    @Override
    public boolean ga2$sameTicks(Object[] snapshot) {
        List<SavedTick<T>> pending = this.pendingTicks;
        int p = pending == null ? 0 : pending.size();
        Object[] queued = this.tickQueue == null ? ga2$all() : null;
        if (p + (queued != null ? queued.length : this.tickQueue.size()) != snapshot.length) {
            return false;
        }
        int i = 0;
        for (int k = 0; k < p; k++) {
            if (pending.get(k) != snapshot[i++]) {
                return false;
            }
        }
        if (queued != null) {
            for (Object tick : queued) {
                if (tick != snapshot[i++]) {
                    return false;
                }
            }
            return true;
        }
        for (Iterator<ScheduledTick<T>> it = this.tickQueue.iterator(); it.hasNext(); ) {
            if (it.next() != snapshot[i++]) {
                return false;
            }
        }
        return true;
    }

    /** The scheduled ticks in the order the save writes them, where another mod replaced the queue. */
    @Unique
    private Object[] ga2$all() {
        return ((LevelChunkTicks<T>) (Object) this).getAll().toArray();
    }
}
