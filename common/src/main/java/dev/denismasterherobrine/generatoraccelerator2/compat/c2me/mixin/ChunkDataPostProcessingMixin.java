package dev.denismasterherobrine.generatoraccelerator2.compat.c2me.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.denismasterherobrine.generatoraccelerator2.compat.c2me.PostProcessingLocks;
import java.util.function.IntFunction;
import java.util.stream.Stream;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The save copies a chunk's post-processing lists while holding their monitors. C2ME's fluid filter may be removing entries
 * from them on a worker ({@link PostProcessingLocks}). The lists are copied by the stream's {@code toArray}, the only one in
 * {@code copyOf}. Only applied with C2ME loaded.
 */
@Mixin(SerializableChunkData.class)
public abstract class ChunkDataPostProcessingMixin {
    @WrapOperation(method = "copyOf",
        at = @At(value = "INVOKE", target = "Ljava/util/stream/Stream;toArray(Ljava/util/function/IntFunction;)[Ljava/lang/Object;"),
        require = 0)
    private static Object[] ga2$copyLocked(Stream<?> stream, IntFunction<?> generator, Operation<Object[]> original,
                                           @Local(argsOnly = true) ChunkAccess chunk) {
        return PostProcessingLocks.pack(chunk.getPostProcessing(), () -> original.call(stream, generator));
    }
}
