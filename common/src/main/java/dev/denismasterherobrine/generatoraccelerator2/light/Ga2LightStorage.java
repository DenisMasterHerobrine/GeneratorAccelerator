package dev.denismasterherobrine.generatoraccelerator2.light;

import javax.annotation.Nullable;
import net.minecraft.world.level.chunk.DataLayer;

/** Fast paths of {@code LayerLightSectionStorage}, implemented by {@code LayerLightSectionStorageMixin}. */
public interface Ga2LightStorage {
    /** {@code getDataLayer(sectionKey, true)}. Reads the working section map through its cache. */
    @Nullable
    DataLayer ga2$layer(long sectionKey);

    /** {@code setStoredLevel(pos, level)}. */
    void ga2$setStoredLevel(long pos, int level);

    /** {@code setStoredLevel(pos, level)} for a block of section {@code sectionKey} ({@code SectionPos.blockToSection(pos)}). */
    void ga2$setStoredLevel(long pos, long sectionKey, int level);
}
