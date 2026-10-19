package dev.denismasterherobrine.generatoraccelerator2.parallellight.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The section map behind {@code getLayer}, read and written without the map's (shared) lookup cache. */
@Mixin(DataLayerStorageMap.class)
public interface DataLayerStorageMapAccessor {
    @Accessor("map")
    Long2ObjectOpenHashMap<DataLayer> ga2pl$map();
}
