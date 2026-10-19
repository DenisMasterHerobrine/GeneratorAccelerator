package dev.denismasterherobrine.generatoraccelerator2.light;

import javax.annotation.Nullable;
import net.minecraft.world.level.chunk.DataLayer;

/** {@code DataLayerStorageMap.getLayer} without the hook's callback object, implemented by {@code DataLayerStorageMapMixin}. */
public interface Ga2LayerMap {
    @Nullable
    DataLayer ga2$layer(long sectionKey);
}
