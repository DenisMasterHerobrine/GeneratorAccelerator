package dev.denismasterherobrine.generatoraccelerator2.mixin.scheduling;

import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.BiomeHand;
import dev.denismasterherobrine.generatoraccelerator2.scheduling.StepLane;
import java.util.concurrent.Executor;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * While GA2 runs vanilla's scheduling, the noise and biome steps queue in {@link StepLane} at their chunk's priority.
 * {@code ChunkStatusStepsMixin} sets that priority. Otherwise, for example under another chunk system or on a direct
 * call, the generator keeps its own executor. The work and the thread pool stay the same.
 */
@Mixin(value = NoiseBasedChunkGenerator.class, priority = 5000)
public abstract class GeneratorExecutorMixin {
    @ModifyArg(method = { "fillFromNoise", "createBiomes" }, require = 0, at = @At(value = "INVOKE",
        target = "Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"),
        index = 1)
    private Executor ga2$laneExecutor(Executor executor) {
        if (BiomeHand.inFill()) {
            // a biome step with the GPU's answers handed to its lookups: they are this thread's, so the step runs here
            return Runnable::run;
        }
        Executor lane = StepLane.currentExecutor();
        return lane != null ? lane : executor;
    }
}
