package dev.denismasterherobrine.generatoraccelerator2.features;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.features.placement.EveryLayer;
import dev.denismasterherobrine.generatoraccelerator2.features.region.RegionReads;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.placement.CountOnEveryLayerPlacement;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * {@link RegionReads} against the chunks' own reads, and {@link EveryLayer} against vanilla's
 * {@code CountOnEveryLayerPlacement.getPositions} (run with the hooks off, so its scans read the chunks the vanilla way): same
 * positions in the same order, and the random source left in the same state. Real nether and overworld terrain, with extra
 * layers on top: floating platforms, water and lava pockets, bedrock, and sections emptied to air or cave air.
 */
public class EveryLayerTest {
    private static final BlockState[] EXTRA = {
        Blocks.STONE.defaultBlockState(), Blocks.NETHERRACK.defaultBlockState(), Blocks.WATER.defaultBlockState(), Blocks.LAVA.defaultBlockState(),
        Blocks.BEDROCK.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState() };

    @SuppressWarnings("deprecation")
    @Test
    void sameAsVanilla() {
        HarnessWorld.Registries2 r = HarnessWorld.registries();
        // 60 a layer draws most columns more than once, so the kept scans are used
        List<IntProvider> counts = List.of(ConstantInt.of(1), ConstantInt.of(4), ConstantInt.of(10), UniformInt.of(0, 6), UniformInt.of(2, 12), ConstantInt.of(60));
        int readsCompared = 0;
        int calls = 0;
        int positions = 0;
        int maxLayers = 0;
        long continuedBefore = EveryLayer.CONTINUED.sum();
        for (HarnessWorld.Kind kind : List.of(HarnessWorld.Kind.NETHER, HarnessWorld.Kind.OVERWORLD)) {
            for (long seed : new long[] { 1L, 77L, 4242L }) {
                LevelHeightAccessor heights = HarnessWorld.heights(r, kind);
                ProtoChunk[] chunks = HarnessWorld.clone(r, FeatureRun.terrain(r, kind, seed), heights);
                if (seed != 1L) {
                    addLayers(chunks, heights, seed);
                }
                HarnessLevel level = new HarnessLevel(chunks, HarnessWorld.RADIUS, FeatureRun.CENTER_X, FeatureRun.CENTER_Z, heights,
                    HarnessWorld.dimension(r, kind), seed, HarnessWorld.randomState(r, kind, seed), r.access, true);
                RegionReads reads = level.ga2$reads();
                assertNotNull(reads, "region reads off in the harness (regionReads not inspected?)");
                PlacementContext ctx = new PlacementContext(level, HarnessWorld.generator(r, kind), Optional.empty());

                RandomSource pick = new XoroshiroRandomSource(seed * 31L + kind.ordinal());
                for (int i = 0; i < 200_000; i++) {
                    int cx = FeatureRun.CENTER_X + pick.nextInt(2 * HarnessWorld.RADIUS + 1) - HarnessWorld.RADIUS;
                    int cz = FeatureRun.CENTER_Z + pick.nextInt(2 * HarnessWorld.RADIUS + 1) - HarnessWorld.RADIUS;
                    int y = heights.getMinBuildHeight() - 24 + pick.nextInt(heights.getHeight() + 48);
                    BlockPos pos = new BlockPos(cx * 16 + pick.nextInt(16), y, cz * 16 + pick.nextInt(16));
                    ProtoChunk chunk = chunks[HarnessWorld.index(cx, cz, FeatureRun.CENTER_X, FeatureRun.CENTER_Z)];
                    assertSame(chunk.getBlockState(pos), reads.read(pos), "block at " + pos);
                    assertSame(chunk.getFluidState(pos), reads.readFluid(pos), "fluid at " + pos);
                    readsCompared++;
                }

                for (IntProvider count : counts) {
                    CountOnEveryLayerPlacement vanilla = CountOnEveryLayerPlacement.of(count);
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            for (int shift = 0; shift < 3; shift++) {
                                // decoration's origin (chunk corner, min build height), and origins whose columns cross into the next chunk
                                int ox = (FeatureRun.CENTER_X + dx) * 16 + shift * 5;
                                int oz = (FeatureRun.CENTER_Z + dz) * 16 + shift * 7;
                                if (Math.abs(((ox + 15) >> 4) - FeatureRun.CENTER_X) > HarnessWorld.RADIUS || Math.abs(((oz + 15) >> 4) - FeatureRun.CENTER_Z) > HarnessWorld.RADIUS) {
                                    continue;
                                }
                                BlockPos origin = new BlockPos(ox, heights.getMinBuildHeight(), oz);
                                long s = seed * 1_000_003L + calls;
                                RandomSource a = new XoroshiroRandomSource(s);
                                RandomSource b = new XoroshiroRandomSource(s);
                                List<BlockPos> expected = new ArrayList<>();
                                FeatureHooks.runVanilla(() -> vanilla.getPositions(ctx, a, origin).forEach(expected::add));
                                List<BlockPos> actual = EveryLayer.positions(count, ctx, b, origin, reads);
                                assertEquals(expected, actual, kind + " seed " + seed + " count " + count + " origin " + origin);
                                assertEquals(a.nextLong(), b.nextLong(), "random state after " + kind + " seed " + seed + " origin " + origin);
                                calls++;
                                positions += actual.size();
                                int layers = 0;
                                for (BlockPos p : actual) {
                                    layers = Math.max(layers, (int) actual.stream().filter(q -> q.getX() == p.getX() && q.getZ() == p.getZ()).count());
                                }
                                maxLayers = Math.max(maxLayers, layers);
                            }
                        }
                    }
                }
            }
        }
        System.out.println("[EveryLayerTest] reads compared " + readsCompared + ", layer searches " + calls + ", positions " + positions
            + ", most grounds found in one column " + maxLayers + ", columns continued " + (EveryLayer.CONTINUED.sum() - continuedBefore));
        assertTrue(positions > 1000, "too few positions found: " + positions);
        assertTrue(EveryLayer.CONTINUED.sum() > continuedBefore, "no column was continued from its kept scan");
    }

    /**
     * Opt-in ({@code -Dga2.bench=true}): nether layer searches, vanilla's against {@link EveryLayer}, best of 7 rounds of the
     * same 2,000 searches each. Vanilla runs with the hooks off, through the harness's chunk lookup and {@code ProtoChunk}
     * reads. The harness's lookup and height accessor are cheaper than a real region's, so this understates vanilla's cost.
     */
    @SuppressWarnings("deprecation")
    @Test
    void bench() {
        Assumptions.assumeTrue(Boolean.getBoolean("ga2.bench"));
        HarnessWorld.Registries2 r = HarnessWorld.registries();
        HarnessWorld.Kind kind = HarnessWorld.Kind.NETHER;
        LevelHeightAccessor heights = HarnessWorld.heights(r, kind);
        ProtoChunk[] chunks = HarnessWorld.clone(r, FeatureRun.terrain(r, kind, 1L), heights);
        HarnessLevel level = new HarnessLevel(chunks, HarnessWorld.RADIUS, FeatureRun.CENTER_X, FeatureRun.CENTER_Z, heights,
            HarnessWorld.dimension(r, kind), 1L, HarnessWorld.randomState(r, kind, 1L), r.access, true);
        RegionReads reads = level.ga2$reads();
        PlacementContext ctx = new PlacementContext(level, HarnessWorld.generator(r, kind), Optional.empty());
        IntProvider count = ConstantInt.of(8);
        CountOnEveryLayerPlacement vanilla = CountOnEveryLayerPlacement.of(count);
        BlockPos origin = new BlockPos(FeatureRun.CENTER_X * 16, heights.getMinBuildHeight(), FeatureRun.CENTER_Z * 16);
        long bestVanilla = Long.MAX_VALUE;
        long bestFast = Long.MAX_VALUE;
        long sink = 0;
        for (int round = 0; round < 7; round++) {
            long[] found = new long[1];
            long t0 = System.nanoTime();
            FeatureHooks.runVanilla(() -> {
                for (int i = 0; i < 2000; i++) {
                    found[0] += vanilla.getPositions(ctx, new XoroshiroRandomSource(i), origin).count();
                }
            });
            long t1 = System.nanoTime();
            for (int i = 0; i < 2000; i++) {
                found[0] -= EveryLayer.positions(count, ctx, new XoroshiroRandomSource(i), origin, reads).size();
            }
            long t2 = System.nanoTime();
            assertEquals(0L, found[0]);
            bestVanilla = Math.min(bestVanilla, t1 - t0);
            bestFast = Math.min(bestFast, t2 - t1);
            sink += found[0];
        }
        System.out.printf("[EveryLayerTest] 2000 nether searches (count 8): vanilla %.2f ms, fast %.2f ms (%.1fx)%s%n", bestVanilla / 1e6, bestFast / 1e6,
            (double) bestVanilla / bestFast, sink == 0 ? "" : "!");
    }

    /** Extra layers: platforms and pockets in random columns, and whole sections set to air or cave air. */
    private static void addLayers(ProtoChunk[] chunks, LevelHeightAccessor heights, long seed) {
        RandomSource random = new XoroshiroRandomSource(seed ^ 0x5DEECE66DL);
        for (ProtoChunk chunk : chunks) {
            for (int k = 0; k < 400; k++) {
                BlockState state = EXTRA[random.nextInt(EXTRA.length)];
                int x = random.nextInt(16);
                int z = random.nextInt(16);
                int y = heights.getMinBuildHeight() + random.nextInt(heights.getHeight());
                int thickness = 1 + random.nextInt(3);
                for (int t = 0; t < thickness && y + t < heights.getMaxBuildHeight(); t++) {
                    set(chunk, heights, x, y + t, z, state);
                }
            }
            if (random.nextInt(3) == 0) {
                int section = random.nextInt(chunk.getSections().length);
                BlockState fill = random.nextBoolean() ? Blocks.AIR.defaultBlockState() : Blocks.CAVE_AIR.defaultBlockState();
                int y0 = heights.getMinBuildHeight() + section * 16;
                for (int y = y0; y < y0 + 16; y++) {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            set(chunk, heights, x, y, z, fill);
                        }
                    }
                }
                // vanilla's recount counts a fluid block twice and setBlockState takes it off once, so a section that held fluid
                // stays "not only air" after this. Recounting half of those sections gives all-air sections. The other half
                // keep the quirk
                if (random.nextBoolean()) {
                    chunk.getSection(section).recalcBlockCounts();
                    assertTrue(chunk.getSection(section).hasOnlyAir());
                }
            }
        }
    }

    private static void set(ProtoChunk chunk, LevelHeightAccessor heights, int x, int y, int z, BlockState state) {
        LevelChunkSection section = chunk.getSection((y >> 4) - (heights.getMinBuildHeight() >> 4));
        section.setBlockState(x, y & 15, z, state, false);
    }
}
