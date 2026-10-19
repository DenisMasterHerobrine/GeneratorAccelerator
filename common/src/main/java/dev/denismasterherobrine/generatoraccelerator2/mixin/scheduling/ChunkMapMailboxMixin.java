package dev.denismasterherobrine.generatoraccelerator2.mixin.scheduling;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.scheduling.MailboxThreads;
import dev.denismasterherobrine.generatoraccelerator2.scheduling.SchedulingConfig;
import java.util.List;
import java.util.concurrent.Executor;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.ProcessorHandle;
import net.minecraft.util.thread.ProcessorMailbox;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Gives the dimension's sorters and its worldgen and light mailboxes their own threads. See {@link MailboxThreads}. */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMailboxMixin {
    @Shadow @Final ServerLevel level;

    @Unique
    private Executor ga2$scheduler;

    @Unique
    private Executor ga2$scheduler() {
        if (this.ga2$scheduler == null) {
            this.ga2$scheduler = MailboxThreads.dedicated("GA2 Chunk Scheduler (" + this.level.dimension().location() + ")");
        }
        return this.ga2$scheduler;
    }

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/util/thread/ProcessorMailbox;create(Ljava/util/concurrent/Executor;Ljava/lang/String;)Lnet/minecraft/util/thread/ProcessorMailbox;"))
    private ProcessorMailbox<Runnable> ga2$ownThreads(Executor executor, String name, Operation<ProcessorMailbox<Runnable>> original) {
        if (SchedulingConfig.dedicatedMailboxThreads()) {
            if ("worldgen".equals(name)) {
                return original.call(this.ga2$scheduler(), name);
            }
            if ("light".equals(name)) {
                return original.call(MailboxThreads.dedicated("GA2 Light (" + this.level.dimension().location() + ")"), name);
            }
        }
        return original.call(executor, name);
    }

    @WrapOperation(method = "<init>", at = @At(value = "NEW",
        target = "(Ljava/util/List;Ljava/util/concurrent/Executor;I)Lnet/minecraft/server/level/ChunkTaskPriorityQueueSorter;"))
    private ChunkTaskPriorityQueueSorter ga2$sorterThread(List<ProcessorHandle<?>> handles, Executor executor, int maxTasks, Operation<ChunkTaskPriorityQueueSorter> original) {
        return original.call(handles, SchedulingConfig.dedicatedMailboxThreads() ? this.ga2$scheduler() : executor, maxTasks);
    }

    /**
     * The distance manager's player ticket throttler is a separate task sorter. Vanilla also runs it on the worker pool.
     * There, every ticket a player's view adds waits behind the queued chunk steps. Building the distance manager waits
     * for the throttler twice. A dimension built while the pool was busy waited half a second. Here the throttler shares
     * the scheduler thread with the chunk task sorter.
     */
    @WrapOperation(method = "<init>", at = @At(value = "NEW",
        target = "(Lnet/minecraft/server/level/ChunkMap;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;)Lnet/minecraft/server/level/ChunkMap$DistanceManager;"))
    private ChunkMap.DistanceManager ga2$throttlerThread(ChunkMap self, Executor executor, Executor mainThread, Operation<ChunkMap.DistanceManager> original) {
        return original.call(self, SchedulingConfig.dedicatedMailboxThreads() ? this.ga2$scheduler() : executor, mainThread);
    }
}
