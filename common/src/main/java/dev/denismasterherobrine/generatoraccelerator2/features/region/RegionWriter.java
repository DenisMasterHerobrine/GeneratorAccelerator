package dev.denismasterherobrine.generatoraccelerator2.features.region;

import dev.denismasterherobrine.generatoraccelerator2.chunkio.HeightmapPriming;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.features.FeatureStats;
import dev.denismasterherobrine.generatoraccelerator2.features.FeaturesConfig;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Transcription of {@code WorldGenRegion.setBlock} (flags are ignored by the region, so there is one
 * write path) and of {@code ProtoChunk.setBlockState} for a plain proto chunk below the light stages.
 * Same lock, same all-air shortcut, same heightmap priming moment and update order, same POI, block
 * entity and post-processing side effects. Any other chunk class or status delegates to the chunk.
 */
public final class RegionWriter {
    private static final HookTargets.Hook UNLOCKED =
        HookTargets.hook(HookTargets.SECTION_WRITE_UNLOCKED);

    private RegionWriter() {}

    /** Exact {@code level.setBlock(pos, state, anyFlags)} on a generation region. */
    public static boolean setBlock(Ga2Region region, BlockPos pos, BlockState state) {
        if (!region.ga2$ensureCanWrite(pos)) {
            return false;
        }
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        ChunkAccess chunk = region.ga2$chunk(cx, cz);
        BlockState old = setBlockState(region, chunk, cx, cz, pos, state);
        if (old != null) {
            region.ga2$onBlockStateChange(pos, old, state);
        }
        if (state.hasBlockEntity()) {
            if (chunk.getStatus().getChunkType() == ChunkStatus.ChunkType.LEVELCHUNK) {
                BlockEntity blockEntity = ((EntityBlock) state.getBlock()).newBlockEntity(pos, state);
                if (blockEntity != null) {
                    chunk.setBlockEntity(blockEntity);
                } else {
                    chunk.removeBlockEntity(pos);
                }
            } else {
                CompoundTag tag = new CompoundTag();
                tag.putInt("x", pos.getX());
                tag.putInt("y", pos.getY());
                tag.putInt("z", pos.getZ());
                tag.putString("id", "DUMMY");
                chunk.setBlockEntityNbt(tag);
            }
        } else if (old != null && old.hasBlockEntity()) {
            chunk.removeBlockEntity(pos);
        }
        if (state.hasPostProcess(region.ga2$level(), pos)) {
            chunk.markPosForPostprocessing(pos);
        }
        return true;
    }

    /** Exact {@code chunk.setBlockState(pos, state, false)}. */
    public static BlockState setBlockState(Ga2Region region, ChunkAccess chunk, int cx, int cz, BlockPos pos, BlockState state) {
        if (chunk.getClass() != ProtoChunk.class || !FeaturesConfig.fastWrites()) {
            if (FeatureStats.DETAILED) {
                FeatureStats.WRITES_DELEGATED.increment();
            }
            return chunk.setBlockState(pos, state, false);
        }
        ChunkStatus status = chunk.getStatus();
        if (status.isOrAfter(ChunkStatus.INITIALIZE_LIGHT) || !status.heightmapsAfter().equals(ChunkStatus.POST_FEATURES)) {
            if (FeatureStats.DETAILED) {
                FeatureStats.WRITES_DELEGATED.increment();
            }
            return chunk.setBlockState(pos, state, false);
        }
        int y = pos.getY();
        if (y < region.ga2$minY() || y >= region.ga2$maxY()) {
            return Blocks.VOID_AIR.defaultBlockState();
        }
        LevelChunkSection section = chunk.getSection((y >> 4) - (region.ga2$minY() >> 4));
        boolean wasEmpty = section.hasOnlyAir();
        if (wasEmpty && state.is(Blocks.AIR)) {
            return state;
        }
        int lx = pos.getX() & 15;
        int ly = y & 15;
        int lz = pos.getZ() & 15;
        // vanilla's ProtoChunk takes the container's thread check here. A decoration holds the area locks of every chunk it writes
        BlockState old = UNLOCKED.allowed() ? section.setBlockState(lx, ly, lz, state, false) : section.setBlockState(lx, ly, lz, state);
        Heightmap[] maps = finalHeightmaps(region, chunk, cx, cz);
        for (Heightmap map : maps) {
            map.update(lx, y, lz, state);
        }
        if (FeatureStats.DETAILED) {
            FeatureStats.WRITES_FAST.increment();
        }
        return old;
    }

    /**
     * The chunk's four final heightmaps in {@code ChunkStatus.POST_FEATURES} iteration order,
     * priming the missing ones first exactly as {@code ProtoChunk.setBlockState} does on that write.
     */
    public static Heightmap[] finalHeightmaps(Ga2Region region, ChunkAccess chunk, int cx, int cz) {
        RegionChunks cache = region.ga2$chunks();
        int slot = cache == null ? -1 : cache.slot(cx, cz);
        if (slot >= 0) {
            Heightmap[] cached = cache.finalHeightmaps(slot);
            if (cached != null) {
                return cached;
            }
        }
        EnumSet<Heightmap.Types> missing = null;
        for (Heightmap.Types type : ChunkStatus.POST_FEATURES) {
            if (!chunk.hasPrimedHeightmap(type)) {
                if (missing == null) {
                    missing = EnumSet.noneOf(Heightmap.Types.class);
                }
                missing.add(type);
            }
        }
        if (missing != null) {
            HeightmapPriming.primeForWrite(chunk, missing, Heightmap::primeHeightmaps);
        }
        Heightmap[] maps = new Heightmap[ChunkStatus.POST_FEATURES.size()];
        int i = 0;
        for (Heightmap.Types type : ChunkStatus.POST_FEATURES) {
            maps[i++] = chunk.getOrCreateHeightmapUnprimed(type);
        }
        if (slot >= 0) {
            cache.setFinalHeightmaps(slot, maps);
        }
        return maps;
    }
}
