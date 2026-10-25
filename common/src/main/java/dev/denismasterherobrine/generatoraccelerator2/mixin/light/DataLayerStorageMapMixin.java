package dev.denismasterherobrine.generatoraccelerator2.mixin.light;

import dev.denismasterherobrine.generatoraccelerator2.light.FastLight;
import dev.denismasterherobrine.generatoraccelerator2.light.Ga2LayerMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Arrays;
import javax.annotation.Nullable;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Section light data lookup with a direct-mapped cache of {@link FastLight#LAYER_SLOTS} sections. Vanilla caches only
 * the last two. As in vanilla, the cache holds only found sections and works only when the map's cache is enabled. The
 * light thread's working map has the cache enabled. The published copy has it disabled. This cache is emptied whenever
 * vanilla empties its own. Every place that replaces or removes a section calls {@code clearCache}. The mixin also holds the
 * snapshot that the next {@code copy} publishes ({@link dev.denismasterherobrine.generatoraccelerator2.light.LightSnapshot}).
 */
@Mixin(value = DataLayerStorageMap.class, priority = 5000)
public abstract class DataLayerStorageMapMixin implements Ga2LayerMap {
    @Shadow private boolean cacheEnabled;
    @Shadow @Final protected Long2ObjectOpenHashMap<DataLayer> map;

    @Unique private final long[] ga2$keys = ga2$emptyKeys();
    @Unique private final DataLayer[] ga2$layers = new DataLayer[FastLight.LAYER_SLOTS];
    /** The section map {@code copy} takes instead of a clone. Set only around one publish on the thread that runs it. */
    @Unique @Nullable private Long2ObjectOpenHashMap<DataLayer> ga2$copyWith;

    @Unique
    private static long[] ga2$emptyKeys() {
        long[] keys = new long[FastLight.LAYER_SLOTS];
        Arrays.fill(keys, Long.MAX_VALUE);
        return keys;
    }

    @Override
    public DataLayer ga2$layer(long key) {
        if (!this.cacheEnabled) {
            return this.map.get(key);
        }
        int slot = FastLight.layerSlot(key);
        if (this.ga2$keys[slot] == key) {
            return this.ga2$layers[slot];
        }
        DataLayer layer = this.map.get(key);
        if (layer != null) {
            this.ga2$keys[slot] = key;
            this.ga2$layers[slot] = layer;
        }
        return layer;
    }

    @Override
    public Long2ObjectOpenHashMap<DataLayer> ga2$map() {
        return this.map;
    }

    @Override
    public void ga2$copyWith(@Nullable Long2ObjectOpenHashMap<DataLayer> map) {
        this.ga2$copyWith = map;
    }

    @Override
    @Nullable
    public Long2ObjectOpenHashMap<DataLayer> ga2$copyWith() {
        return this.ga2$copyWith;
    }

    @Inject(method = "getLayer", at = @At("HEAD"), cancellable = true)
    private void ga2$getLayer(long key, CallbackInfoReturnable<DataLayer> cir) {
        if (FastLight.on && this.cacheEnabled) {
            cir.setReturnValue(this.ga2$layer(key));
        }
    }

    @Inject(method = "clearCache", at = @At("RETURN"))
    private void ga2$clearCache(CallbackInfo ci) {
        if (this.ga2$keys != null) {
            Arrays.fill(this.ga2$keys, Long.MAX_VALUE);
            Arrays.fill(this.ga2$layers, null);
        }
    }
}
