package dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.chunkload.ChunkLoadStats;
import java.util.function.IntSupplier;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Times how long a player ticket waits in the throttler for a slot. */
@Mixin(targets = "net.minecraft.server.level.DistanceManager$PlayerTicketTracker")
public abstract class PlayerTicketTrackerMixin {
    @WrapOperation(method = "onLevelChange(JIZZ)V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ChunkTaskPriorityQueueSorter;message(Ljava/lang/Runnable;JLjava/util/function/IntSupplier;)Lnet/minecraft/server/level/ChunkTaskPriorityQueueSorter$Message;"))
    private ChunkTaskPriorityQueueSorter.Message<Runnable> ga2$timeSlotWait(Runnable task, long pos, IntSupplier level,
        Operation<ChunkTaskPriorityQueueSorter.Message<Runnable>> original) {
        long queued = System.nanoTime();
        ChunkLoadStats.TICKETS_QUEUED.increment();
        Runnable timed = () -> {
            ChunkLoadStats.SLOT_WAIT.record(System.nanoTime() - queued);
            task.run();
        };
        return original.call(timed, pos, level);
    }
}
