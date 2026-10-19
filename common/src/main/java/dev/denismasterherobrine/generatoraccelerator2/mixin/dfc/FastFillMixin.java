package dev.denismasterherobrine.generatoraccelerator2.mixin.dfc;

import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.FastFill;
import net.minecraft.core.Holder;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
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
 * Runs the noise fill through {@link FastFill} when the NoiseChunk of the chunk qualifies. Applied late so the mixin
 * plugin can record other mods' changes to {@code doFill} here. If another mod changes it, FastFill stays off.
 */
@Mixin(value = NoiseBasedChunkGenerator.class, priority = 5000)
public abstract class FastFillMixin {
    @Shadow @Final private Holder<NoiseGeneratorSettings> settings;

    @Shadow
    private NoiseChunk createNoiseChunk(ChunkAccess chunk, StructureManager structures, Blender blender, RandomState randomState) {
        throw new AssertionError();
    }

    @Inject(method = "doFill", at = @At("HEAD"), cancellable = true)
    private void ga2$fastFill(Blender blender, StructureManager structures, RandomState randomState, ChunkAccess chunk, int minCellY, int cellCountY,
                              CallbackInfoReturnable<ChunkAccess> cir) {
        if (!FastFill.ENABLED) {
            return;
        }
        // the same NoiseChunk that vanilla's doFill would get. The chunk keeps the NoiseChunk once it is made.
        NoiseChunk noisechunk = chunk.getOrCreateNoiseChunk(c -> this.createNoiseChunk(c, structures, blender, randomState));
        if (FastFill.applies(noisechunk)) {
            cir.setReturnValue(FastFill.fill(chunk, noisechunk, this.settings.value().defaultBlock(), minCellY, cellCountY));
        }
    }
}
