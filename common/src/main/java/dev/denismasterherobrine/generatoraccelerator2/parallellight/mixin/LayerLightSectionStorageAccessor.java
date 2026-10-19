package dev.denismasterherobrine.generatoraccelerator2.parallellight.mixin;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Section storage of a light layer: the working map, the two "changed" sets and the end of a batch. */
@Mixin(LayerLightSectionStorage.class)
public interface LayerLightSectionStorageAccessor {
    @Accessor("updatingSectionData")
    DataLayerStorageMap<?> ga2pl$updatingSectionData();

    @Accessor("changedSections")
    LongSet ga2pl$changedSections();

    @Accessor("sectionsAffectedByLightUpdates")
    LongSet ga2pl$sectionsAffectedByLightUpdates();

    @Invoker("lightOnInSection")
    boolean ga2pl$lightOnInSection(long sectionKey);

    @Invoker("markNewInconsistencies")
    void ga2pl$markNewInconsistencies(LightEngine<?, ?> engine);

    @Invoker("swapSectionMap")
    void ga2pl$swapSectionMap();
}
