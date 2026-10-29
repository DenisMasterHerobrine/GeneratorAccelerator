package dev.denismasterherobrine.generatoraccelerator2.features;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.denismasterherobrine.generatoraccelerator2.chunkio.HeightmapPriming;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.Test;

/** The final heightmaps primed from the worldgen maps equal vanilla's scan, on real terrain of every preset. */
public class HeightmapPrimingTest {
    private static final int[][] CHUNKS = { { 0, 0 }, { 17, -5 }, { -300, 412 }, { 2800, 2800 }, { 40, 41 } };

    @Test
    void matchesVanillaScan() {
        long fastBefore = HeightmapPriming.FAST.sum();
        int compared = 0;
        for (TerrainHarness.Preset preset : TerrainHarness.Preset.values()) {
            for (long seed : new long[] { 1234567L, -42L }) {
                TerrainHarness.World world = TerrainHarness.world(preset, seed);
                for (int[] c : CHUNKS) {
                    ProtoChunk vanilla = world.noise(c[0], c[1]);
                    ProtoChunk fast = world.noise(c[0], c[1]);
                    Heightmap.primeHeightmaps(vanilla, ChunkStatus.FINAL_HEIGHTMAPS);
                    HeightmapPriming.prime(fast, ChunkStatus.FINAL_HEIGHTMAPS, Heightmap::primeHeightmaps);
                    for (Heightmap.Types type : ChunkStatus.FINAL_HEIGHTMAPS) {
                        assertEquals(vanilla.hasPrimedHeightmap(type), fast.hasPrimedHeightmap(type), "presence " + type);
                        assertArrayEquals(vanilla.getOrCreateHeightmapUnprimed(type).getRawData(), fast.getOrCreateHeightmapUnprimed(type).getRawData(),
                            type + ", " + preset + " seed " + seed + " chunk " + c[0] + "," + c[1]);
                    }
                    compared++;
                }
            }
        }
        assertEquals(compared, HeightmapPriming.FAST.sum() - fastBefore, "chunks primed the fast way");
    }

    /**
     * A chunk at CARVERS that a neighbour has decorated into has its final heightmaps primed by that write and updated
     * since. Its worldgen heightmaps are left behind. Those final maps are kept as they are, and equal a fresh scan.
     */
    @Test
    void keepsFinalHeightmapsAWriteHasPrimed() {
        TerrainHarness.World world = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L);
        long keptBefore = HeightmapPriming.KEPT.sum();
        for (int[] c : CHUNKS) {
            ProtoChunk vanilla = world.noise(c[0], c[1]);
            ProtoChunk fast = world.noise(c[0], c[1]);
            for (ProtoChunk chunk : new ProtoChunk[] { vanilla, fast }) {
                chunk.setPersistedStatus(ChunkStatus.CARVERS);
                // a "neighbour's" writes: a pillar above the surface and a hole into it (the first primes the final maps)
                int top = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG).getFirstAvailable(5, 7);
                chunk.setBlockState(new BlockPos(5, top + 3, 7), Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
                chunk.setBlockState(new BlockPos(5, top + 4, 7), Blocks.OAK_LEAVES.defaultBlockState(), Block.UPDATE_ALL);
                int top2 = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG).getFirstAvailable(9, 2);
                chunk.setBlockState(new BlockPos(9, top2 - 1, 2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                chunk.setBlockState(new BlockPos(9, top2 - 2, 2), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
            }
            Heightmap.primeHeightmaps(vanilla, ChunkStatus.FINAL_HEIGHTMAPS);
            HeightmapPriming.prime(fast, ChunkStatus.FINAL_HEIGHTMAPS, Heightmap::primeHeightmaps);
            for (Heightmap.Types type : ChunkStatus.FINAL_HEIGHTMAPS) {
                assertArrayEquals(vanilla.getOrCreateHeightmapUnprimed(type).getRawData(), fast.getOrCreateHeightmapUnprimed(type).getRawData(),
                    type + ", chunk " + c[0] + "," + c[1]);
            }
        }
        assertEquals(CHUNKS.length, HeightmapPriming.KEPT.sum() - keptBefore, "chunks whose final heightmaps were kept");
    }
}
