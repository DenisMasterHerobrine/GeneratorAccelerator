package dev.denismasterherobrine.generatoraccelerator2.aquifer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.FastFill;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.util.Arrays;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.Test;

/**
 * Better Caves' liquid-region step as GA2 applies it ({@link LiquidRegions}): the step itself against the mod's handler, read off
 * its bytecode, and the noise fill with whole cells against the fill block by block, both applying synthetic tables (the fill's
 * whole-cell air path must stand down at and below the liquid altitude).
 */
public class LiquidRegionsTest {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState LAVA = Blocks.LAVA.defaultBlockState();

    @Test
    void stepMatchesTheHandler() {
        BlockState[] blocks = new BlockState[256];
        blocks[0] = LAVA;
        blocks[1] = WATER;
        blocks[2] = null;
        blocks[17] = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 3);
        LiquidRegions.Table t = new LiquidRegions.Table(10, blocks);
        // solid stays solid, above the altitude nothing changes
        assertNull(LiquidRegions.apply(t, 0, 5, 0, null));
        assertSame(AIR, LiquidRegions.apply(t, 0, 11, 0, AIR));
        // at and below it: the column's liquid, unless the block is of that block already
        assertSame(LAVA, LiquidRegions.apply(t, 0, 10, 0, AIR));
        assertSame(LAVA, LiquidRegions.apply(t, 0, 10, 0, WATER));
        assertSame(LAVA, LiquidRegions.apply(t, 16, 3, 0, LAVA));
        assertSame(WATER, LiquidRegions.apply(t, 0, -60, 1, AIR));
        assertSame(WATER, LiquidRegions.apply(t, 0, -60, 1, WATER));
        // a column without liquid makes the block solid
        assertNull(LiquidRegions.apply(t, 0, 0, 2, AIR));
        assertNull(LiquidRegions.apply(t, 0, 0, 2, WATER));
        // same block, another state: the aquifer's state is kept
        assertSame(WATER, LiquidRegions.apply(t, 1, 0, 1, WATER));
        // the GPU's entries. A liquid state the program has no id for (the flowing water above) keeps the chunk on the CPU
        BlockState[] states = { AIR, Blocks.STONE.defaultBlockState(), WATER, LAVA };
        assertNull(LiquidRegions.gpu(t, states), "a liquid without an id");
        blocks[17] = null;
        int[] gpu = LiquidRegions.gpu(t, states);
        assertEquals(10, gpu[0]);
        assertEquals(3 | LiquidRegions.GPU_SAME_LAVA, gpu[1]);
        assertEquals(2 | LiquidRegions.GPU_SAME_WATER, gpu[2]);
        assertEquals(LiquidRegions.GPU_NULL, gpu[3]);
        assertEquals(LiquidRegions.GPU_NULL, gpu[18]);
    }

    @Test
    void wholeCellsRespectTheAltitude() {
        TerrainHarness.World world = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L);
        LiquidRegions.testTables = LiquidRegionsTest::table;
        try {
            assertTrue(LiquidRegions.active());
            int compared = 0;
            for (int[] c : new int[][] { { 0, 0 }, { 17, -5 }, { -300, 412 }, { 2800, 2800 }, { 123, 456 } }) {
                ProtoChunk perBlock;
                try {
                    FastFill.ENABLED = false;
                    perBlock = world.noise(c[0], c[1]);
                } finally {
                    FastFill.ENABLED = true;
                }
                ProtoChunk whole = world.noise(c[0], c[1]);
                sameChunk(perBlock, whole, "chunk " + c[0] + "," + c[1]);
                // the table did act: liquid at the altitude where the per-block fill left air above it
                int cx = c[0] * 16, cz = c[1] * 16;
                LiquidRegions.Table t = table(new ChunkPos(c[0], c[1]));
                boolean liquidSeen = false;
                for (int lx = 0; lx < 16 && !liquidSeen; lx++) {
                    for (int lz = 0; lz < 16 && !liquidSeen; lz++) {
                        BlockState s = perBlock.getBlockState(new BlockPos(cx + lx, t.altitude(), cz + lz));
                        liquidSeen = !s.getFluidState().isEmpty() && t.blocks()[lx * 16 + lz] != null && s.is(t.blocks()[lx * 16 + lz].getBlock());
                    }
                }
                assertTrue(liquidSeen, "the liquid regions acted in chunk " + c[0] + "," + c[1]);
                compared++;
            }
            System.out.println("LiquidRegionsTest: " + compared + " chunks the same with whole cells and block by block under synthetic liquid regions");
        } finally {
            LiquidRegions.testTables = null;
        }
    }

    /** Synthetic regions: an altitude per chunk around the sea, lava, water or nothing per column. */
    public static LiquidRegions.Table table(ChunkPos pos) {
        Random r = new Random(pos.toLong() * 31 + 7);
        BlockState[] blocks = new BlockState[256];
        for (int i = 0; i < 256; i++) {
            int k = r.nextInt(10);
            blocks[i] = k < 4 ? LAVA : k < 8 ? WATER : null;
        }
        return new LiquidRegions.Table(40 + r.nextInt(50), blocks);
    }

    static void sameChunk(ProtoChunk a, ProtoChunk b, String where) {
        LevelChunkSection[] sa = a.getSections();
        LevelChunkSection[] sb = b.getSections();
        for (int s = 0; s < sa.length; s++) {
            assertEquals(sa[s].nonEmptyBlockCount, sb[s].nonEmptyBlockCount, "non-empty count, section " + s + ", " + where);
            assertEquals(sa[s].tickingFluidCount, sb[s].tickingFluidCount, "fluid count, section " + s + ", " + where);
            var da = sa[s].getStates().data;
            var db = sb[s].getStates().data;
            assertEquals(da.palette().getSize(), db.palette().getSize(), "palette size, section " + s + ", " + where);
            for (int p = 0; p < da.palette().getSize(); p++) {
                assertSame(da.palette().valueFor(p), db.palette().valueFor(p), "palette entry " + p + ", section " + s + ", " + where);
            }
            assertArrayEquals(da.storage().getRaw(), db.storage().getRaw(), "storage, section " + s + ", " + where);
        }
        for (var type : Heightmap.Types.values()) {
            assertEquals(a.hasPrimedHeightmap(type), b.hasPrimedHeightmap(type), "heightmap presence " + type + ", " + where);
            if (a.hasPrimedHeightmap(type)) {
                assertArrayEquals(a.getOrCreateHeightmapUnprimed(type).getRawData(), b.getOrCreateHeightmapUnprimed(type).getRawData(), "heightmap " + type + ", " + where);
            }
        }
        assertEquals(Arrays.deepToString(a.getPostProcessing()), Arrays.deepToString(b.getPostProcessing()), "post-processing, " + where);
    }
}
