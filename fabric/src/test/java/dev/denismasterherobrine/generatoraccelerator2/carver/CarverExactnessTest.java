package dev.denismasterherobrine.generatoraccelerator2.carver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.features.verify.FeatureCompare;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.GenerationStep;
import org.junit.jupiter.api.Test;

/**
 * Carves real terrain (noise and surface done by the vanilla pipeline) twice from identical copies:
 * once through a transcription of vanilla's {@code applyCarvers} calling the vanilla carvers, once
 * through {@link CarverRunner}, and compares every block, the carving mask, heightmaps, section
 * counters and post-processing lists. Runs each site twice so the second pass replays cached paths.
 */
class CarverExactnessTest {
    private static final long[] SEEDS = { 1234567L, -42L, 8_675_309L };

    @Test
    void hookApplied() {
        assertTrue(HookTargets.inspected(HookTargets.CARVER_ENTRY), "carver mixin was not applied");
        assertTrue(HookTargets.allowed(HookTargets.CARVER_ENTRY), "carver hook stood down: " + HookTargets.foreign(HookTargets.CARVER_ENTRY));
    }

    @Test
    void overworldCarvingMatches() {
        CarverPaths.clear();
        for (long seed : SEEDS) {
            List<int[]> sites = new ArrayList<>();
            for (int i = 0; i < 14; i++) {
                sites.add(new int[] { i * 5 - 33, (i * 7) % 23 - 11 });
            }
            check(TerrainHarness.Preset.OVERWORLD, seed, sites, false);
        }
        assertTrue(CarverPaths.HITS.sum() > 0, "path cache never hit");
    }

    @Test
    void netherAndAmplifiedMatch() {
        check(TerrainHarness.Preset.NETHER, SEEDS[0], List.of(new int[] { 0, 0 }, new int[] { 3, -9 }, new int[] { -14, 6 }, new int[] { 21, 21 }), false);
        check(TerrainHarness.Preset.AMPLIFIED, SEEDS[1], List.of(new int[] { 2, 2 }, new int[] { -9, 30 }), false);
    }

    @Test
    void additionalMaskMatches() {
        check(TerrainHarness.Preset.OVERWORLD, SEEDS[0], List.of(new int[] { 4, 4 }, new int[] { -6, 10 }), true);
    }

    private static ProtoChunk afterSurface(TerrainHarness.Site site) {
        ProtoChunk chunk = site.center();
        TerrainHarness.vanillaSurface(site, chunk);
        chunk.setStatus(ChunkStatus.SURFACE);
        return chunk;
    }

    private static void check(TerrainHarness.Preset preset, long seed, List<int[]> sites, boolean additionalMask) {
        List<String> failures = new ArrayList<>();
        int carved = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int[] at : sites) {
                TerrainHarness.Site site = TerrainHarness.site(preset, seed, at[0], at[1]);
                TerrainHarness.World w = site.world;
                ProtoChunk expected = afterSurface(site);
                ProtoChunk actual = afterSurface(site);
                if (additionalMask) {
                    CarvingMask.Mask extra = (x, y, z) -> ((x * 7 + y * 3 + z) & 7) == 0;
                    expected.getOrCreateCarvingMask(GenerationStep.Carving.AIR).setAdditionalMask(extra);
                    actual.getOrCreateCarvingMask(GenerationStep.Carving.AIR).setAdditionalMask(extra);
                }
                TerrainHarness.vanillaCarvers(w, expected, TerrainHarness.carverNeighbourhood(w, expected));
                TerrainHarness.ChunkLookup lookup = TerrainHarness.carverNeighbourhood(w, actual);
                long before = CarverRunner.CHUNKS_FAST.sum();
                boolean ran = CarverRunner.apply(w.generator, lookup::get, w.registries.access, w.seed, w.randomState,
                    new BiomeManager((x, y, z) -> { throw new IllegalStateException("level biome source used"); }, w.zoomSeed), actual, GenerationStep.Carving.AIR);
                assertTrue(ran, "GA2 carver runner declined " + preset + " " + at[0] + "," + at[1]);
                assertEquals(before + 1, CarverRunner.CHUNKS_FAST.sum());
                List<String> diff = FeatureCompare.compare(expected, actual, 12);
                if (!diff.isEmpty()) {
                    failures.add(preset + " seed " + seed + " pass " + pass + " chunk " + at[0] + "," + at[1] + ": " + String.join("\n    ", diff));
                }
                if (expected.getCarvingMask(GenerationStep.Carving.AIR).stream(expected.getPos()).findAny().isPresent()) {
                    carved++;
                }
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
        assertTrue(carved > 0, preset + ": no site was carved at all (harness problem)");
        System.out.println("[CarverExactnessTest] " + preset + " seed " + seed + ": " + carved + " carved site runs of " + sites.size() * 2);
    }
}
