package dev.denismasterherobrine.generatoraccelerator2.mixin.scheduling;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import dev.denismasterherobrine.generatoraccelerator2.scheduling.StepThreads;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ChunkGenerationTask;
import org.spongepowered.asm.mixin.Mixin;

/** Marks the thread running vanilla's generation task loop, see {@link StepThreads}. */
@Mixin(ChunkGenerationTask.class)
public abstract class ChunkGenerationTaskMixin {
    @WrapMethod(method = "runUntilWait")
    private CompletableFuture<?> ga2$markMailbox(Operation<CompletableFuture<?>> original) {
        return StepThreads.runOnMailbox(original::call);
    }
}
