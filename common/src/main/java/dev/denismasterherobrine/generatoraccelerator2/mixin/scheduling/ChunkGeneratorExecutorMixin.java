package dev.denismasterherobrine.generatoraccelerator2.mixin.scheduling;

import dev.denismasterherobrine.generatoraccelerator2.scheduling.StepLane;
import java.util.concurrent.Executor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Same as {@link GeneratorExecutorMixin}, for the biome step of generators that keep {@code ChunkGenerator.createBiomes}. */
@Mixin(value = ChunkGenerator.class, priority = 5000)
public abstract class ChunkGeneratorExecutorMixin {
    @ModifyArg(method = "createBiomes", require = 0, at = @At(value = "INVOKE",
        target = "Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"),
        index = 1)
    private Executor ga2$laneExecutor(Executor executor) {
        Executor lane = StepLane.currentExecutor();
        return lane != null ? lane : executor;
    }
}
