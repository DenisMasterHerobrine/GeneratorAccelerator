package dev.denismasterherobrine.generatoraccelerator2.mixin.storage;

import dev.denismasterherobrine.generatoraccelerator2.storage.ParallelRead;
import java.io.DataInputStream;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Marks a chunk stored in its own file ({@code .mcc}), which holds chunks too large for the region file. The stream of such
 * a chunk reads that file during the parse, so {@link ParallelRead} parses the chunk on the IO thread.
 */
@Mixin(value = RegionFile.class, priority = 5000)
public abstract class RegionFileReadMixin {
    @Inject(method = "createExternalChunkInputStream", at = @At("HEAD"))
    private void ga2$external(ChunkPos pos, byte version, CallbackInfoReturnable<DataInputStream> cir) {
        ParallelRead.EXTERNAL.get()[0] = true;
    }
}
