package dev.denismasterherobrine.generatoraccelerator2.mixin.storage;

import dev.denismasterherobrine.generatoraccelerator2.storage.PostProcessingTags;
import it.unimi.dsi.fastutil.shorts.ShortList;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Packs a saved chunk's post-processing lists into their tags directly ({@link PostProcessingTags}). Applied late so the mixin plugin
 * sees whether another mod changes {@code packOffsets}. If one does, this mixin stands down.
 */
@Mixin(value = ChunkSerializer.class, priority = 5000)
public abstract class ChunkSerializerOffsetsMixin {
    @Inject(method = "packOffsets", at = @At("HEAD"), cancellable = true)
    private static void ga2$packOffsets(ShortList[] lists, CallbackInfoReturnable<ListTag> cir) {
        if (PostProcessingTags.enabled()) {
            cir.setReturnValue(PostProcessingTags.pack(lists));
        }
    }
}
