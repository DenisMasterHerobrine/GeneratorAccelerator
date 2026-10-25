package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import dev.denismasterherobrine.generatoraccelerator2.features.FeatureHooks;
import dev.denismasterherobrine.generatoraccelerator2.features.FeaturesCompat;
import java.util.function.Consumer;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code getAll} collects the distinct values of a container by scanning every entry into an int
 * set. A container whose palette holds one value can only yield that value, so it is handed over
 * directly. Decoration calls this for every section of the 3x3 chunks around each chunk to collect
 * their biomes, and nearly every biome section has a single-value palette. Otherwise, for a palette
 * of at most 64 values, the entries are still scanned, but the ids seen are kept in a bit mask instead
 * of the int set's linear search. The values are emitted in the same order (first appearance).
 */
@Mixin(value = PalettedContainer.class, priority = 5000)
public abstract class PalettedContainerMixin<T> {
    @Inject(method = "getAll", at = @At("HEAD"), cancellable = true)
    private void ga2$getAllSingle(Consumer<T> consumer, CallbackInfo ci) {
        if (!FeatureHooks.enabled() || !FeaturesCompat.decorationAllowed()) {
            return;
        }
        @SuppressWarnings("unchecked")
        PalettedContainer.Data<T> data = ((PalettedContainer<T>) (Object) this).data;
        Palette<T> palette = data.palette();
        int size = palette.getSize();
        if (size == 1) {
            consumer.accept(palette.valueFor(0));
            ci.cancel();
        } else if (size <= 64) {
            // a consumer that itself queries a container takes the next level's instance
            dev.denismasterherobrine.generatoraccelerator2.features.FirstSeenIds seen = dev.denismasterherobrine.generatoraccelerator2.features.FirstSeenIds.take();
            try {
                data.storage().getAll(seen);
                for (int i = 0, n = seen.count; i < n; i++) {
                    consumer.accept(palette.valueFor(seen.order[i]));
                }
            } finally {
                dev.denismasterherobrine.generatoraccelerator2.features.FirstSeenIds.give();
            }
            ci.cancel();
        }
    }
}
