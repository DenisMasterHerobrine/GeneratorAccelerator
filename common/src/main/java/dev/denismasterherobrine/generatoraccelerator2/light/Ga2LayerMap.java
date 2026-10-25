package dev.denismasterherobrine.generatoraccelerator2.light;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import javax.annotation.Nullable;
import net.minecraft.world.level.chunk.DataLayer;

/** {@code DataLayerStorageMap} access for GA2's light hooks, implemented by {@code DataLayerStorageMapMixin}. */
public interface Ga2LayerMap {
    /** {@code getLayer} without the hook's callback object. */
    @Nullable
    DataLayer ga2$layer(long sectionKey);

    /** The section map behind {@code getLayer}. */
    Long2ObjectOpenHashMap<DataLayer> ga2$map();

    /** Sets the section map the next {@code copy} takes instead of a clone ({@link Ga2SnapshotCopy}). Null clones again. */
    void ga2$copyWith(@Nullable Long2ObjectOpenHashMap<DataLayer> map);

    /** The map set by {@link #ga2$copyWith}, or null. */
    @Nullable
    Long2ObjectOpenHashMap<DataLayer> ga2$copyWith();
}
