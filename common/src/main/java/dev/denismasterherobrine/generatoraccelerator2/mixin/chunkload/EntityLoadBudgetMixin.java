package dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.chunkload.ChunkLoadConfig;
import dev.denismasterherobrine.generatoraccelerator2.chunkload.ChunkLoadStats;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import java.util.Queue;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Spreads the entities of loaded chunks over ticks ({@link ChunkLoadConfig#entityLoadBudgetNanos}). Each tick, vanilla adds
 * the entities of every chunk whose entities finished loading. Here the tick stops taking chunks once the budget is used.
 * It always takes at least one. The rest stay queued in order for the next tick. Until its entities are added, a chunk's
 * entities count as still loading, as they did while they were read. Saving the world still adds all of them first.
 */
@Mixin(PersistentEntitySectionManager.class)
public abstract class EntityLoadBudgetMixin {
    @Unique
    private static final HookTargets.Hook GA2$HOOK = HookTargets.hook(HookTargets.CHUNK_ENTITY_BUDGET);

    /** 0 outside a tick's additions or without a budget. */
    @Unique
    private long ga2$deadline;
    @Unique
    private int ga2$taken;

    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/entity/PersistentEntitySectionManager;processPendingLoads()V"))
    private void ga2$budgetedLoads(PersistentEntitySectionManager self, Operation<Void> original) {
        long start = System.nanoTime();
        long budget = ChunkLoadConfig.entityLoadBudgetNanos();
        this.ga2$deadline = budget > 0 && GA2$HOOK.allowed() ? start + budget : 0;
        this.ga2$taken = 0;
        try {
            original.call(self);
        } finally {
            this.ga2$deadline = 0;
        }
        if (this.ga2$taken > 0) {
            ChunkLoadStats.ENTITY_CHUNKS.add(this.ga2$taken);
            ChunkLoadStats.ENTITY_ADDS.record(System.nanoTime() - start);
        }
    }

    @WrapOperation(method = "processPendingLoads", at = @At(value = "INVOKE", target = "Ljava/util/Queue;poll()Ljava/lang/Object;"))
    private Object ga2$pollWithinBudget(Queue<?> inbox, Operation<Object> original) {
        long deadline = this.ga2$deadline;
        if (deadline != 0 && this.ga2$taken > 0 && System.nanoTime() > deadline && !inbox.isEmpty()) {
            ChunkLoadStats.ENTITY_DEFERRED.increment();
            return null;
        }
        Object next = original.call(inbox);
        if (next != null) {
            this.ga2$taken++;
        }
        return next;
    }
}
