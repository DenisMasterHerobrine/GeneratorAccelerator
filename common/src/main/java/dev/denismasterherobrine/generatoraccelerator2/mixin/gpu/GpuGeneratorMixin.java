package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import dev.denismasterherobrine.generatoraccelerator2.gpu.GpuSteps;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Answers the BIOMES and NOISE steps from the GPU ({@link GpuSteps}) when the GPU can decide the chunk exactly. Otherwise, and on
 * any GPU failure, the generator's own methods run. Applied late so the mixin plugin can record other mods' changes to these
 * methods ({@code gpu.noise}, {@code gpu.biomes}). After such a change, the GPU path stays off.
 */
@Mixin(value = NoiseBasedChunkGenerator.class, priority = 5000)
public abstract class GpuGeneratorMixin {
    @Shadow @Final private Holder<NoiseGeneratorSettings> settings;
    @Shadow @Final private Supplier<Aquifer.FluidPicker> globalFluidPicker;

    @Inject(method = "fillFromNoise", at = @At("HEAD"), cancellable = true)
    private void ga2$gpuNoise(Blender blender, RandomState randomState, StructureManager structures, ChunkAccess chunk,
                              CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        CompletableFuture<ChunkAccess> f = GpuSteps.fillFromNoise((NoiseBasedChunkGenerator) (Object) this, this.settings, this.globalFluidPicker, blender, randomState,
            structures, chunk);
        if (f != null) {
            cir.setReturnValue(f);
        }
    }

    @Inject(method = "createBiomes", at = @At("HEAD"), cancellable = true)
    private void ga2$gpuBiomes(RandomState randomState, Blender blender, StructureManager structures, ChunkAccess chunk,
                               CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        CompletableFuture<ChunkAccess> f = GpuSteps.createBiomes((NoiseBasedChunkGenerator) (Object) this, this.settings, this.globalFluidPicker, randomState, blender,
            structures, chunk);
        if (f != null) {
            cir.setReturnValue(f);
        }
    }
}
