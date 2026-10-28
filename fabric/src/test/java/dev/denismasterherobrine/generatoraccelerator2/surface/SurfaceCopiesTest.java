package dev.denismasterherobrine.generatoraccelerator2.surface;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.features.verify.FeatureCompare;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import org.junit.jupiter.api.Test;

/** A thread's own surface system ({@link SurfaceCopies}) builds every block the dimension's shared system builds. */
class SurfaceCopiesTest {
    private static final long SEED = 1234567L;

    @Test
    void copyBuildsTheSameSurfaces() {
        TerrainHarness.World w = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, SEED);
        SurfaceSystem shared = w.randomState.surfaceSystem();
        SurfaceSystem copy = SurfaceCopies.copy(shared, w.randomState);
        assertNotSame(shared, copy);
        List<String> failures = new ArrayList<>();
        for (int[] at : SurfaceExactnessTest.sites(TerrainHarness.Preset.OVERWORLD, SEED).subList(0, 10)) {
            TerrainHarness.Site site = TerrainHarness.site(TerrainHarness.Preset.OVERWORLD, SEED, at[0], at[1]);
            ProtoChunk expected = site.center();
            ProtoChunk actual = site.center();
            SurfaceHooks.runVanilla(() -> build(w, site, expected, shared));
            SurfaceHooks.runVanilla(() -> build(w, site, actual, copy));
            List<String> diff = FeatureCompare.compare(expected, actual, 12);
            if (!diff.isEmpty()) {
                failures.add("chunk " + at[0] + "," + at[1] + ": " + String.join("\n    ", diff));
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    private static void build(TerrainHarness.World w, TerrainHarness.Site site, ProtoChunk chunk, SurfaceSystem system) {
        system.buildSurface(w.randomState, site.surfaceBiomeManager(chunk), w.registries.biomes, w.settings.useLegacyRandomSource(),
            w.generationContext(), chunk, w.noiseChunk(chunk), w.settings.surfaceRule());
    }
}
