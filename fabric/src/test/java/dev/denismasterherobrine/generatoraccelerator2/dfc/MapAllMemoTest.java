package dev.denismasterherobrine.generatoraccelerator2.dfc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.denismasterherobrine.generatoraccelerator2.aquifer.AquiferFastPath;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.BoundSampler;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.FastFill;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.FillHeights;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.LazyInterpolation;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.MapAllMemo;
import dev.denismasterherobrine.generatoraccelerator2.mixin.dfc.NoiseChunkAccessor;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.util.Arrays;
import java.util.List;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The router mapping memo of a NoiseChunk changes nothing: chunks built with it and without it get the same
 * interpolators in the same order and the same biomes and blocks.
 */
public class MapAllMemoTest {
    private static final int[][] CHUNKS = { { 0, 0 }, { 17, -5 }, { -300, 412 }, { 2800, 2800 } };

    @Test
    void lazyInterpolationChangesNothing() {
        int compared = 0;
        for (TerrainHarness.Preset preset : TerrainHarness.Preset.values()) {
            for (long seed : new long[] { 1234567L, -42L }) {
                TerrainHarness.World world = TerrainHarness.world(preset, seed);
                for (int[] c : CHUNKS) {
                    ProtoChunk eager;
                    try {
                        LazyInterpolation.ENABLED = false;
                        eager = world.noise(c[0], c[1]);
                    } finally {
                        LazyInterpolation.ENABLED = true;
                    }
                    ProtoChunk lazy = world.noise(c[0], c[1]);
                    sameChunk(eager, lazy, preset + " seed " + seed + " chunk " + c[0] + "," + c[1]);
                    compared++;
                }
            }
        }
        assertEquals(TerrainHarness.Preset.values().length * 2 * CHUNKS.length, compared);
    }

    @Test
    void fastFillChangesNothing() {
        long before = FastFill.FILLS.sum();
        for (TerrainHarness.Preset preset : TerrainHarness.Preset.values()) {
            for (long seed : new long[] { 1234567L, -42L }) {
                TerrainHarness.World world = TerrainHarness.world(preset, seed);
                for (int[] c : CHUNKS) {
                    ProtoChunk vanillaLoop;
                    try {
                        FastFill.ENABLED = false;
                        vanillaLoop = world.noise(c[0], c[1]);
                    } finally {
                        FastFill.ENABLED = true;
                    }
                    sameChunk(vanillaLoop, world.noise(c[0], c[1]), preset + " seed " + seed + " chunk " + c[0] + "," + c[1]);
                }
            }
        }
        assertEquals(TerrainHarness.Preset.values().length * 2L * CHUNKS.length,
            FastFill.FILLS.sum() - before, "chunks filled by FastFill");
    }

    /** The whole-cell paths (all-air and all-solid cells from the corners, {@code CellBounds}) write what the per-block path writes. */
    @Test
    void cellClassificationChangesNothing() {
        var fill = FastFill.class;
        long sky = FastFill.CELLS_SKY.sum();
        long solid = FastFill.CELLS_SOLID.sum();
        long solidVeins = FastFill.CELLS_SOLID_VEINS.sum();
        for (TerrainHarness.Preset preset : TerrainHarness.Preset.values()) {
            for (long seed : new long[] { 1234567L, -42L }) {
                TerrainHarness.World world = TerrainHarness.world(preset, seed);
                for (int[] c : CHUNKS) {
                    ProtoChunk perBlock;
                    try {
                        FastFill.CLASSIFY_CELLS = false;
                        perBlock = world.noise(c[0], c[1]);
                    } finally {
                        FastFill.CLASSIFY_CELLS = true;
                    }
                    sameChunk(perBlock, world.noise(c[0], c[1]), preset + " seed " + seed + " chunk " + c[0] + "," + c[1]);
                }
            }
        }
        long skyNow = FastFill.CELLS_SKY.sum() - sky;
        long solidNow = FastFill.CELLS_SOLID.sum() - solid;
        long solidVeinsNow = FastFill.CELLS_SOLID_VEINS.sum() - solidVeins;
        System.out.println("cells handled whole: " + skyNow + " air, " + solidNow + " solid, " + solidVeinsNow + " solid with the vein rule (" + fill.getSimpleName() + ")");
        Assertions.assertTrue(skyNow > 0, "air cells");
        Assertions.assertTrue(solidNow > 0, "solid cells");
        Assertions.assertTrue(solidVeinsNow > 0, "solid cells with the vein rule");

        // an aquifer in search mode (order-sensitive status fields) classifies neighbourhoods late. Air cells then come from that
        // late classification
        TerrainHarness.World searchWorld = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 99L);
        AquiferFastPath.registerRandomState(searchWorld.randomState.aquiferRandom(), false);
        long skyBefore = FastFill.CELLS_SKY.sum();
        for (int[] c : CHUNKS) {
            ProtoChunk perBlock;
            try {
                // every block searched, as vanilla does: no whole cells and no answers from known statuses
                FastFill.CLASSIFY_CELLS = false;
                AquiferFastPath.AIR_BOXES = false;
                perBlock = searchWorld.noise(c[0], c[1]);
            } finally {
                FastFill.CLASSIFY_CELLS = true;
                AquiferFastPath.AIR_BOXES = true;
            }
            sameChunk(perBlock, searchWorld.noise(c[0], c[1]), "search-mode aquifer, chunk " + c[0] + "," + c[1]);
        }
        Assertions.assertTrue(FastFill.CELLS_SKY.sum() > skyBefore,
            "air cells with a search-mode aquifer (late classification)");
    }

    @Test
    void samplerColumnsChangeNothing() {
        for (TerrainHarness.Preset preset : TerrainHarness.Preset.values()) {
            for (long seed : new long[] { 1234567L, -42L }) {
                TerrainHarness.World world = TerrainHarness.world(preset, seed);
                for (int[] c : CHUNKS) {
                    ProtoChunk perPoint;
                    try {
                        BoundSampler.COLUMNS = false;
                        perPoint = world.noise(c[0], c[1]);
                    } finally {
                        BoundSampler.COLUMNS = true;
                    }
                    sameChunk(perPoint, world.noise(c[0], c[1]), preset + " seed " + seed + " chunk " + c[0] + "," + c[1]);
                }
            }
        }
    }

    @Test
    void columnHeightmapsChangeNothing() {
        for (TerrainHarness.Preset preset : TerrainHarness.Preset.values()) {
            for (long seed : new long[] { 1234567L, -42L }) {
                TerrainHarness.World world = TerrainHarness.world(preset, seed);
                for (int[] c : CHUNKS) {
                    ProtoChunk perBlock;
                    try {
                        FillHeights.ENABLED = false;
                        perBlock = world.noise(c[0], c[1]);
                    } finally {
                        FillHeights.ENABLED = true;
                    }
                    sameChunk(perBlock, world.noise(c[0], c[1]), preset + " seed " + seed + " chunk " + c[0] + "," + c[1]);
                }
            }
        }
    }

    private static void sameChunk(ProtoChunk a, ProtoChunk b, String where) {
        LevelChunkSection[] sa = a.getSections();
        LevelChunkSection[] sb = b.getSections();
        for (int s = 0; s < sa.length; s++) {
            // the in-memory form that is saved: block counts, palette entries in id order, raw storage
            assertEquals(sa[s].nonEmptyBlockCount, sb[s].nonEmptyBlockCount, "non-empty count, section " + s + ", " + where);
            assertEquals(sa[s].tickingBlockCount, sb[s].tickingBlockCount, "ticking count, section " + s + ", " + where);
            assertEquals(sa[s].tickingFluidCount, sb[s].tickingFluidCount, "fluid count, section " + s + ", " + where);
            var da = sa[s].getStates().data;
            var db = sb[s].getStates().data;
            assertEquals(da.palette().getClass(), db.palette().getClass(), "palette type, section " + s + ", " + where);
            assertEquals(da.palette().getSize(), db.palette().getSize(), "palette size, section " + s + ", " + where);
            for (int p = 0; p < da.palette().getSize(); p++) {
                assertSame(da.palette().valueFor(p), db.palette().valueFor(p), "palette entry " + p + ", section " + s + ", " + where);
            }
            Assertions.assertArrayEquals(da.storage().getRaw(), db.storage().getRaw(), "storage, section " + s + ", " + where);
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        assertSame(sa[s].getBlockState(x, y, z), sb[s].getBlockState(x, y, z), "block " + x + "," + (s * 16 + y) + "," + z + ", " + where);
                    }
                }
            }
        }
        for (var type : Heightmap.Types.values()) {
            if (a.hasPrimedHeightmap(type) != b.hasPrimedHeightmap(type)) {
                throw new AssertionError("heightmap presence " + type + ", " + where);
            }
            if (a.hasPrimedHeightmap(type)) {
                Assertions.assertArrayEquals(a.getOrCreateHeightmapUnprimed(type).getRawData(), b.getOrCreateHeightmapUnprimed(type).getRawData(), "heightmap " + type + ", " + where);
            }
        }
        Assertions.assertEquals(Arrays.deepToString(a.getPostProcessing()), Arrays.deepToString(b.getPostProcessing()), "post-processing, " + where);
    }

    @Test
    void memoChangesNothing() {
        int compared = 0;
        for (TerrainHarness.Preset preset : TerrainHarness.Preset.values()) {
            for (long seed : new long[] { 1234567L, -42L }) {
                TerrainHarness.World world = TerrainHarness.world(preset, seed);
                for (int[] c : CHUNKS) {
                    ProtoChunk plain;
                    try {
                        MapAllMemo.setEnabled(false);
                        plain = world.noise(c[0], c[1]);
                    } finally {
                        MapAllMemo.setEnabled(true);
                    }
                    ProtoChunk memo = world.noise(c[0], c[1]);
                    String where = preset + " seed " + seed + " chunk " + c[0] + "," + c[1];
                    List<NoiseChunk.NoiseInterpolator> a = ((NoiseChunkAccessor) world.noiseChunk(plain)).ga2$interpolators();
                    List<NoiseChunk.NoiseInterpolator> b = ((NoiseChunkAccessor) world.noiseChunk(memo)).ga2$interpolators();
                    assertEquals(a.size(), b.size(), "interpolator count, " + where);
                    assertEquals(((NoiseChunkAccessor) world.noiseChunk(plain)).ga2$cellCaches().size(),
                        ((NoiseChunkAccessor) world.noiseChunk(memo)).ga2$cellCaches().size(), "cell cache count, " + where);
                    LevelChunkSection[] sa = plain.getSections();
                    LevelChunkSection[] sb = memo.getSections();
                    for (int s = 0; s < sa.length; s++) {
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                for (int x = 0; x < 16; x++) {
                                    assertSame(sa[s].getBlockState(x, y, z), sb[s].getBlockState(x, y, z), "block " + x + "," + (s * 16 + y) + "," + z + ", " + where);
                                }
                            }
                        }
                        for (int y = 0; y < 4; y++) {
                            for (int z = 0; z < 4; z++) {
                                for (int x = 0; x < 4; x++) {
                                    assertSame(sa[s].getNoiseBiome(x, y, z), sb[s].getNoiseBiome(x, y, z), "biome, " + where);
                                }
                            }
                        }
                    }
                    compared++;
                }
            }
        }
        assertEquals(TerrainHarness.Preset.values().length * 2 * CHUNKS.length, compared);
    }
}
