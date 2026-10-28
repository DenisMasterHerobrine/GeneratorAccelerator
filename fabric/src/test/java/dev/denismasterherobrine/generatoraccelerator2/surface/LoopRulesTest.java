package dev.denismasterherobrine.generatoraccelerator2.surface;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.features.verify.FeatureCompare;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.junit.jupiter.api.Test;

/** Vanilla's surface loop with the compiled rules ({@link LoopRules}) builds every block vanilla's own rules build. */
class LoopRulesTest {
    private static final long SEED = 1234567L;

    @Test
    void hookApplied() {
        assertTrue(HookTargets.inspected(HookTargets.SURFACE_LOOP_RULES), "SurfaceSystem mixin was not applied");
        assertTrue(HookTargets.allowed(HookTargets.SURFACE_LOOP_RULES), "loop rules stood down: " + HookTargets.foreign(HookTargets.SURFACE_LOOP_RULES));
    }

    @Test
    void overworldMatches() {
        check(TerrainHarness.Preset.OVERWORLD, SEED, SurfaceExactnessTest.sites(TerrainHarness.Preset.OVERWORLD, SEED).subList(0, 12));
    }

    @Test
    void otherPresetsMatch() {
        check(TerrainHarness.Preset.AMPLIFIED, -42L, List.of(new int[] { 3, 3 }, new int[] { -20, 41 }));
        check(TerrainHarness.Preset.NETHER, SEED, List.of(new int[] { 0, 0 }, new int[] { 11, -5 }, new int[] { -23, 17 }));
        check(TerrainHarness.Preset.END, 8_675_309L, List.of(new int[] { 0, 0 }, new int[] { 70, 3 }));
    }

    private static void check(TerrainHarness.Preset preset, long seed, List<int[]> sites) {
        TerrainHarness.World w = TerrainHarness.world(preset, seed);
        long before = LoopRules.CHUNKS.sum();
        List<String> failures = new ArrayList<>();
        for (int[] at : sites) {
            TerrainHarness.Site site = TerrainHarness.site(preset, seed, at[0], at[1]);
            ProtoChunk expected = site.center();
            ProtoChunk actual = site.center();
            SurfaceHooks.runVanilla(() -> build(w, site, expected));
            SurfaceHooks.runLoopRules(() -> build(w, site, actual));
            List<String> diff = FeatureCompare.compare(expected, actual, 12);
            if (!diff.isEmpty()) {
                failures.add(preset + " chunk " + at[0] + "," + at[1] + ": " + String.join("\n    ", diff));
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
        assertTrue(LoopRules.CHUNKS.sum() >= before + sites.size(), preset + ": vanilla's loop did not use the compiled rules");
    }

    private static void build(TerrainHarness.World w, TerrainHarness.Site site, ProtoChunk chunk) {
        w.randomState.surfaceSystem().buildSurface(w.randomState, site.surfaceBiomeManager(chunk), w.registries.biomes, w.settings.useLegacyRandomSource(),
            w.generationContext(), chunk, w.noiseChunk(chunk), w.settings.surfaceRule());
    }
}
