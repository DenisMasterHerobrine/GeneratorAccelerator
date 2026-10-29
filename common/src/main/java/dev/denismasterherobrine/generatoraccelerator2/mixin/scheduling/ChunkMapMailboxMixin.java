package dev.denismasterherobrine.generatoraccelerator2.mixin.scheduling;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.scheduling.MailboxThreads;
import dev.denismasterherobrine.generatoraccelerator2.scheduling.SchedulingConfig;
import java.util.concurrent.Executor;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskDispatcher;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.ConsecutiveExecutor;
import net.minecraft.util.thread.TaskScheduler;
import net.minecraft.world.level.TicketStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Gives the dimension's task dispatchers and its worldgen and light executors their own threads. See {@link MailboxThreads}. */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMailboxMixin {
    @Shadow @Final ServerLevel level;

    @Unique
    private Executor ga2$scheduler;

    @Unique
    private Executor ga2$scheduler() {
        if (this.ga2$scheduler == null) {
            this.ga2$scheduler = MailboxThreads.dedicated("GA2 Chunk Scheduler (" + this.level.dimension().identifier() + ")");
        }
        return this.ga2$scheduler;
    }

    @WrapOperation(method = "<init>", at = @At(value = "NEW",
        target = "(Ljava/util/concurrent/Executor;Ljava/lang/String;)Lnet/minecraft/util/thread/ConsecutiveExecutor;"))
    private ConsecutiveExecutor ga2$ownThreads(Executor executor, String name, Operation<ConsecutiveExecutor> original) {
        if (SchedulingConfig.dedicatedMailboxThreads()) {
            if ("worldgen".equals(name)) {
                return original.call(this.ga2$scheduler(), name);
            }
            if ("light".equals(name)) {
                return original.call(MailboxThreads.dedicated("GA2 Light (" + this.level.dimension().identifier() + ")"), name);
            }
        }
        return original.call(executor, name);
    }

    @WrapOperation(method = "<init>", at = @At(value = "NEW",
        target = "(Lnet/minecraft/util/thread/TaskScheduler;Ljava/util/concurrent/Executor;)Lnet/minecraft/server/level/ChunkTaskDispatcher;"))
    private ChunkTaskDispatcher ga2$dispatcherThread(TaskScheduler<Runnable> executor, Executor dispatcherExecutor, Operation<ChunkTaskDispatcher> original) {
        return original.call(executor, SchedulingConfig.dedicatedMailboxThreads() ? this.ga2$scheduler() : dispatcherExecutor);
    }

    /**
     * The distance manager's player ticket throttler is a separate task dispatcher. Vanilla also runs it on the worker pool.
     * There, every ticket a player's view adds waits behind the queued chunk steps. Here the throttler shares the scheduler
     * thread with the chunk task dispatchers.
     */
    @WrapOperation(method = "<init>", at = @At(value = "NEW",
        target = "(Lnet/minecraft/server/level/ChunkMap;Lnet/minecraft/world/level/TicketStorage;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;)Lnet/minecraft/server/level/ChunkMap$DistanceManager;"))
    private ChunkMap.DistanceManager ga2$throttlerThread(ChunkMap self, TicketStorage tickets, Executor executor, Executor mainThread,
                                                        Operation<ChunkMap.DistanceManager> original) {
        return original.call(self, tickets, SchedulingConfig.dedicatedMailboxThreads() ? this.ga2$scheduler() : executor, mainThread);
    }
}
