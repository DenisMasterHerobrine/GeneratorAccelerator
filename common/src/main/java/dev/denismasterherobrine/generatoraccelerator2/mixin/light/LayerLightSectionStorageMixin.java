package dev.denismasterherobrine.generatoraccelerator2.mixin.light;

import dev.denismasterherobrine.generatoraccelerator2.light.FastLight;
import dev.denismasterherobrine.generatoraccelerator2.light.Ga2LayerMap;
import dev.denismasterherobrine.generatoraccelerator2.light.Ga2LightStorage;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fast {@code setStoredLevel}, which runs for every light level written. Vanilla adds the block's section to
 * {@code changedSections} and copies the section data the first time in a batch. Vanilla also adds the block's section
 * and the neighbour sections the block touches to {@code sectionsAffectedByLightUpdates}. Both sets only grow until
 * {@code swapSectionMap} clears them, so adding a section already added changes nothing. This mixin remembers the last
 * section added to {@code changedSections}, and the sections recently added to {@code sectionsAffectedByLightUpdates} in
 * a 16-slot direct-mapped cache. It skips those repeats. Everything else is vanilla's body.
 */
@Mixin(value = LayerLightSectionStorage.class, priority = 5000)
public abstract class LayerLightSectionStorageMixin<M extends DataLayerStorageMap<M>> implements Ga2LightStorage {
    @Shadow @Final protected LongSet changedSections;
    @Shadow @Final protected LongSet sectionsAffectedByLightUpdates;
    @Shadow @Final protected M updatingSectionData;

    @Unique private long ga2$lastChanged = Long.MAX_VALUE;
    /** Direct-mapped record of sections recently added to {@code sectionsAffectedByLightUpdates}. Reset with that set. */
    @Unique private final long[] ga2$affected = ga2$emptyAffected();

    @Unique
    private static long[] ga2$emptyAffected() {
        long[] keys = new long[16];
        java.util.Arrays.fill(keys, Long.MAX_VALUE);
        return keys;
    }

    /** Adds a section to the affected set unless the section is known to be there. The set only grows until cleared. */
    @Unique
    private void ga2$affect(long section) {
        int slot = (int) (section ^ section >>> 20 ^ section >>> 42) & 15;
        if (this.ga2$affected[slot] != section) {
            this.sectionsAffectedByLightUpdates.add(section);
            this.ga2$affected[slot] = section;
        }
    }

    @Override
    public DataLayer ga2$layer(long sectionKey) {
        return ((Ga2LayerMap) this.updatingSectionData).ga2$layer(sectionKey);
    }

    @Override
    public void ga2$setStoredLevel(long pos, int level) {
        this.ga2$setStoredLevel(pos, SectionPos.blockToSection(pos), level);
    }

    @Override
    public void ga2$setStoredLevel(long pos, long section, int level) {
        DataLayer layer;
        if (section != this.ga2$lastChanged && this.changedSections.add(section)) {
            layer = this.updatingSectionData.copyDataLayer(section);
        } else {
            layer = this.ga2$layer(section);
        }
        this.ga2$lastChanged = section;
        int x = BlockPos.getX(pos);
        int y = BlockPos.getY(pos);
        int z = BlockPos.getZ(pos);
        layer.set(x & 15, y & 15, z & 15, level);
        if (((x + 1) & 15) > 1 && ((y + 1) & 15) > 1 && ((z + 1) & 15) > 1) {
            // Not on a section face. Vanilla adds only this section.
            this.ga2$affect(section);
        } else {
            // Same as SectionPos.aroundAndAtBlockPos: every section the block's 3x3x3 neighbourhood touches, in the same order
            int x0 = SectionPos.blockToSectionCoord(x - 1);
            int x1 = SectionPos.blockToSectionCoord(x + 1);
            int y0 = SectionPos.blockToSectionCoord(y - 1);
            int y1 = SectionPos.blockToSectionCoord(y + 1);
            int z0 = SectionPos.blockToSectionCoord(z - 1);
            int z1 = SectionPos.blockToSectionCoord(z + 1);
            for (int sx = x0; sx <= x1; sx++) {
                for (int sy = y0; sy <= y1; sy++) {
                    for (int sz = z0; sz <= z1; sz++) {
                        this.ga2$affect(SectionPos.asLong(sx, sy, sz));
                    }
                }
            }
        }
    }

    @Inject(method = "setStoredLevel", at = @At("HEAD"), cancellable = true)
    private void ga2$setStoredLevel(long pos, int level, CallbackInfo ci) {
        if (FastLight.on) {
            this.ga2$setStoredLevel(pos, level);
            ci.cancel();
        }
    }

    @Inject(method = "swapSectionMap", at = @At("HEAD"))
    private void ga2$swapSectionMap(CallbackInfo ci) {
        this.ga2$lastChanged = Long.MAX_VALUE;
        java.util.Arrays.fill(this.ga2$affected, Long.MAX_VALUE);
    }
}
