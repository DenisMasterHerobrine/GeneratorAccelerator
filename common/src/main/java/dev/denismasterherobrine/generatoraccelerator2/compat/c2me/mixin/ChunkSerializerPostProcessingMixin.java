package dev.denismasterherobrine.generatoraccelerator2.compat.c2me.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.compat.c2me.PostProcessingLocks;
import it.unimi.dsi.fastutil.shorts.ShortList;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The save packs a chunk's post-processing lists while holding their monitors. C2ME's fluid filter may be removing entries
 * from them on a worker ({@link PostProcessingLocks}). Only applied with C2ME loaded.
 */
@Mixin(ChunkSerializer.class)
public abstract class ChunkSerializerPostProcessingMixin {
    @WrapOperation(method = "write",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/storage/ChunkSerializer;packOffsets([Lit/unimi/dsi/fastutil/shorts/ShortList;)Lnet/minecraft/nbt/ListTag;"),
        require = 0)
    private static ListTag ga2$packLocked(ShortList[] lists, Operation<ListTag> original) {
        // one argument, not spread as varargs
        return PostProcessingLocks.pack(lists, () -> original.call((Object) lists));
    }
}
