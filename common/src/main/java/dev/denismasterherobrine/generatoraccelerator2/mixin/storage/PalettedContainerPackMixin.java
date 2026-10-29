package dev.denismasterherobrine.generatoraccelerator2.mixin.storage;

import dev.denismasterherobrine.generatoraccelerator2.storage.FastPack;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Saving a chunk packs every section's block states and biomes ({@link FastPack}). Applied late so the mixin plugin
 * sees whether another mod changes {@code pack}. If one does, this mixin stands down.
 */
@Mixin(value = PalettedContainer.class, priority = 5000)
public abstract class PalettedContainerPackMixin<T> {
    @Inject(method = "pack", at = @At("HEAD"), cancellable = true)
    private void ga2$pack(Strategy<T> strategy, CallbackInfoReturnable<PalettedContainerRO.PackedData<T>> cir) {
        if (!FastPack.enabled()) {
            return;
        }
        @SuppressWarnings("unchecked")
        PalettedContainer<T> self = (PalettedContainer<T>) (Object) this;
        self.acquire();
        try {
            PalettedContainerRO.PackedData<T> packed = FastPack.pack(self.data, strategy);
            if (packed != null) {
                cir.setReturnValue(packed);
            }
        } finally {
            self.release();
        }
    }
}
