package dev.denismasterherobrine.generatoraccelerator2.chunkio;

import dev.denismasterherobrine.generatoraccelerator2.gpu.Ga2GpuChunk;
import dev.denismasterherobrine.generatoraccelerator2.gpu.GpuTerrain;
import dev.denismasterherobrine.generatoraccelerator2.mixin.dfc.PalettedContainerInvoker;
import dev.denismasterherobrine.generatoraccelerator2.mixin.scheduling.ChunkAccessAccessor;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import it.unimi.dsi.fastutil.shorts.ShortList;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * A detached copy of a proto chunk between generation steps. Used to check a fast path against vanilla on the same input
 * ({@code -Dgeneratoraccelerator2.verify.*}). The copy holds blocks and biomes per section, primed heightmaps,
 * post-processing marks, carving masks (without an old-world blending filter), and the persisted status. It shares the
 * original's NoiseChunk. The steps only read the NoiseChunk, and its caches are memoised pure functions.
 */
public final class ChunkCopy {
    private ChunkCopy() {}

    @SuppressWarnings("unchecked")
    public static ProtoChunk copy(ProtoChunk chunk, Registry<Biome> biomes) {
        ProtoChunk copy = new ProtoChunk(chunk.getPos(), UpgradeData.EMPTY, chunk.getHeightAccessorForGeneration(), biomes, chunk.getBlendingData());
        LevelChunkSection[] from = chunk.getSections();
        LevelChunkSection[] to = copy.getSections();
        for (int i = 0; i < from.length; i++) {
            // Not PalettedContainer.copy(). That copy keeps the original's palette or resize callback. A write that needs a
            // bigger palette in the copy would then resize the original instead.
            to[i] = new LevelChunkSection(exact(from[i].getStates(), 16), exact((PalettedContainer<Holder<Biome>>) from[i].getBiomes(), 4));
            // The constructor recounts, which counts a fluid twice. The original's counts were kept per write, so copy them.
            copyCounters(from[i], to[i]);
        }
        for (Heightmap.Types type : Heightmap.Types.values()) {
            if (chunk.hasPrimedHeightmap(type)) {
                copy.setHeightmap(type, chunk.getOrCreateHeightmapUnprimed(type).getRawData().clone());
            }
        }
        ShortList[] marks = chunk.getPostProcessing();
        for (int i = 0; i < marks.length; i++) {
            if (marks[i] != null) {
                for (int j = 0; j < marks[i].size(); j++) {
                    copy.addPackedPostProcess(marks[i].getShort(j), i);
                }
            }
        }
        for (GenerationStep.Carving step : GenerationStep.Carving.values()) {
            CarvingMask mask = chunk.getCarvingMask(step);
            if (mask != null) {
                copy.setCarvingMask(step, new CarvingMask(mask.toArray(), chunk.getMinBuildHeight()));
            }
        }
        copy.setStatus(chunk.getStatus());
        ((ChunkAccessAccessor) copy).ga2$setNoiseChunk(((ChunkAccessAccessor) chunk).ga2$noiseChunk());
        GpuTerrain terrain = GpuTerrain.of(chunk);
        if (terrain != null) {
            // The GPU's surface levels and aquifer cells, read by the chunk instead of a NoiseChunk. The copy gets its own aquifer.
            ((Ga2GpuChunk) copy).ga2$setGpuTerrain(terrain.copy());
        }
        return copy;
    }

    /**
     * Saves the primitive instance fields of {@code o}, including superclass fields. The returned action restores them. This is
     * for state a step reads before writing it. An example is the aquifer's "schedule a fluid update" flag, which the carvers
     * read after a lava block. That flag is left over from the aquifer's last computation. A vanilla run on a copy must not
     * change the state the fast path then starts from. Reference fields (the memoised caches) are left alone.
     */
    public static Runnable snapshot(Object o) {
        List<Field> fields = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            Field[] declared;
            try {
                declared = c.getDeclaredFields();
            } catch (LinkageError e) {
                continue; // A field's type is a class missing on this side.
            }
            for (Field f : declared) {
                int m = f.getModifiers();
                if (Modifier.isStatic(m) || Modifier.isFinal(m) || !f.getType().isPrimitive()) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    values.add(f.get(o));
                    fields.add(f);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // Not accessible. The field is left as it is.
                }
            }
        }
        return () -> {
            for (int i = 0; i < fields.size(); i++) {
                try {
                    fields.get(i).set(o, values.get(i));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // left as it is
                }
            }
        };
    }

    /**
     * A container with the original's palette entries in the same order, the same storage bits and words, and its own resize
     * callback. The copy therefore stores the blocks exactly as the original does. A step run on the copy leaves its sections as
     * it would leave the original's. When the original's layout cannot be reproduced (a global palette), the container is
     * rebuilt block by block.
     */
    @SuppressWarnings("unchecked")
    private static <T> PalettedContainer<T> exact(PalettedContainer<T> from, int size) {
        PalettedContainer.Data<T> d = from.data;
        int bits = d.storage().getBits();
        if (bits > 8) {
            return rebuilt(from, size);
        }
        PalettedContainer<T> to = from.recreate();
        // the storage's own bits, else the smaller request a growing palette was made from: Lithium's strategy gives a linear palette of
        // 4 bits for 1 bit and its hash palette for 4
        PalettedContainer.Data<T> nd = null;
        for (int req = bits; req >= 0 && nd == null; req--) {
            PalettedContainer.Data<T> c = ((PalettedContainerInvoker) (Object) to).ga2$createOrReuseData(null, req);
            if (c.palette().getClass() == d.palette().getClass() && c.storage().getBits() == bits) {
                nd = c;
            }
        }
        if (nd == null) {
            return rebuilt(from, size);
        }
        for (int i = 0; i < d.palette().getSize(); i++) {
            nd.palette().idFor(d.palette().valueFor(i));
        }
        long[] raw = d.storage().getRaw(), nraw = nd.storage().getRaw();
        if (nd.palette().getSize() != d.palette().getSize() || nd.palette().getClass() != d.palette().getClass() || nd.storage().getBits() != bits
            || nraw.length != raw.length) {
            return rebuilt(from, size);
        }
        System.arraycopy(raw, 0, nraw, 0, raw.length);
        to.data = nd;
        return to;
    }

    /** A new container of the same kind holding the same values (its own palette, resized as values are set). */
    private static <T> PalettedContainer<T> rebuilt(PalettedContainer<T> from, int size) {
        PalettedContainer<T> to = from.recreate();
        for (int y = 0; y < size; y++) {
            for (int z = 0; z < size; z++) {
                for (int x = 0; x < size; x++) {
                    to.getAndSetUnchecked(x, y, z, from.get(x, y, z));
                }
            }
        }
        return to;
    }

    private static final String[] COUNTERS = {"nonEmptyBlockCount", "tickingBlockCount", "tickingFluidCount"};

    private static void copyCounters(LevelChunkSection from, LevelChunkSection to) {
        for (String name : COUNTERS) {
            try {
                Field f = LevelChunkSection.class.getDeclaredField(Names.runtimeField("net/minecraft/world/level/chunk/LevelChunkSection", name));
                f.setAccessible(true);
                f.setShort(to, f.getShort(from));
            } catch (ReflectiveOperationException | RuntimeException e) {
                return;
            }
        }
    }
}
