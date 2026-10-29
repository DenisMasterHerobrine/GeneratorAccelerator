package dev.denismasterherobrine.generatoraccelerator2.chunkio;

import dev.denismasterherobrine.generatoraccelerator2.gpu.Ga2GpuChunk;
import dev.denismasterherobrine.generatoraccelerator2.gpu.GpuTerrain;
import dev.denismasterherobrine.generatoraccelerator2.mixin.scheduling.ChunkAccessAccessor;
import it.unimi.dsi.fastutil.shorts.ShortList;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.UpgradeData;
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

    /** The container factory of the last biome registry asked for. It only makes the copy's first sections, which the copies replace. */
    private static volatile Factory factory;

    private record Factory(Registry<Biome> biomes, PalettedContainerFactory containers) {}

    /** {@code PalettedContainerFactory.create} for a biome registry alone. */
    public static PalettedContainerFactory factory(Registry<Biome> biomes) {
        Factory f = factory;
        if (f == null || f.biomes() != biomes) {
            Strategy<BlockState> states = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
            BlockState air = Blocks.AIR.defaultBlockState();
            Strategy<Holder<Biome>> biomeStrategy = Strategy.createForBiomes(biomes.asHolderIdMap());
            Holder<Biome> plains = biomes.getOrThrow(Biomes.PLAINS);
            f = new Factory(biomes, new PalettedContainerFactory(states, air, PalettedContainer.codecRW(BlockState.CODEC, states, air), biomeStrategy, plains,
                PalettedContainer.codecRO(biomes.holderByNameCodec(), biomeStrategy, plains)));
            factory = f;
        }
        return f.containers();
    }

    public static ProtoChunk copy(ProtoChunk chunk, Registry<Biome> biomes) {
        ProtoChunk copy = new ProtoChunk(chunk.getPos(), UpgradeData.EMPTY, chunk.getHeightAccessorForGeneration(), factory(biomes), chunk.getBlendingData());
        LevelChunkSection[] from = chunk.getSections();
        LevelChunkSection[] to = copy.getSections();
        for (int i = 0; i < from.length; i++) {
            // The copy has its own storage and palette, in the original's layout, and the original's counters
            to[i] = from[i].copy();
        }
        for (Heightmap.Types type : Heightmap.Types.values()) {
            if (chunk.hasPrimedHeightmap(type)) {
                copy.setHeightmap(type, chunk.getOrCreateHeightmapUnprimed(type).getRawData().clone());
            }
        }
        ShortList[] marks = chunk.getPostProcessing();
        for (int i = 0; i < marks.length; i++) {
            if (marks[i] != null) {
                copy.addPackedPostProcess(marks[i], i);
            }
        }
        CarvingMask mask = chunk.getCarvingMask();
        if (mask != null) {
            copy.setCarvingMask(new CarvingMask(mask.toArray(), chunk.getMinY()));
        }
        copy.setPersistedStatus(chunk.getPersistedStatus());
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
}
