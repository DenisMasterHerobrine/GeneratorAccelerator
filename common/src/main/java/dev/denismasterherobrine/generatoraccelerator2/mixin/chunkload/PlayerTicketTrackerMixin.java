package dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.chunkload.ChunkLoadStats;
import java.util.function.IntSupplier;
import net.minecraft.server.level.ThrottlingChunkTaskDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Times how long a player ticket waits in the throttler for a slot. */
@Mixin(targets = "net.minecraft.server.level.DistanceManager$PlayerTicketTracker")
public abstract class PlayerTicketTrackerMixin {
    @WrapOperation(method = "onLevelChange(JIZZ)V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ThrottlingChunkTaskDispatcher;submit(Ljava/lang/Runnable;JLjava/util/function/IntSupplier;)V"))
    private void ga2$timeSlotWait(ThrottlingChunkTaskDispatcher dispatcher, Runnable task, long pos, IntSupplier level, Operation<Void> original) {
        long queued = System.nanoTime();
        ChunkLoadStats.TICKETS_QUEUED.increment();
        Runnable timed = () -> {
            ChunkLoadStats.SLOT_WAIT.record(System.nanoTime() - queued);
            task.run();
        };
        original.call(dispatcher, timed, pos, level);
    }
}
