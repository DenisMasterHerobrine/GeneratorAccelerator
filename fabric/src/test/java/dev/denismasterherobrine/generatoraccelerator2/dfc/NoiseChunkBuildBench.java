package dev.denismasterherobrine.generatoraccelerator2.dfc;

import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.util.Locale;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Cost of building a chunk's NoiseChunk (mapping the router through its wrap visitor) and of hashing and comparing the
 * router's density function records, which that mapping does for every node. For comparing Java versions.
 * {@code gradlew test --tests "*NoiseChunkBuildBench*" -Dga2.bench=true [-Pga2.testJava=25]}
 */
@EnabledIfSystemProperty(named = "ga2.bench", matches = "true")
public class NoiseChunkBuildBench {
    @Test
    void build() {
        TerrainHarness.World world = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L);
        RandomState state = world.randomState;
        NoiseRouter router = state.router();
        DensityFunction[] fields = { router.finalDensity(), router.continents(), router.erosion(), router.depth(), router.ridges(), router.temperature(),
            router.vegetation(), router.preliminarySurfaceLevel(), router.veinToggle(), router.veinRidged(), router.veinGap(), router.barrierNoise() };
        int origin = 0;
        for (int round = 0; round < 8; round++) {
            long t0 = System.nanoTime();
            int chunks = 400;
            for (int i = 0; i < chunks; i++) {
                world.noiseChunk(world.emptyChunk(origin + i % 20, round * 40 + i / 20));
            }
            long build = System.nanoTime() - t0;
            origin += 64;
            long t1 = System.nanoTime();
            int hash = 0;
            int reps = 20000;
            for (int r = 0; r < reps; r++) {
                for (DensityFunction f : fields) {
                    hash += f.hashCode();
                }
            }
            long hashing = System.nanoTime() - t1;
            long t2 = System.nanoTime();
            int equal = 0;
            for (int r = 0; r < reps / 10; r++) {
                for (DensityFunction f : fields) {
                    if (f.equals(f.mapAll(DensityFunction.Visitor.class.cast((DensityFunction.Visitor) g -> g)))) {
                        equal++;
                    }
                }
            }
            long equality = System.nanoTime() - t2;
            System.out.println(String.format(Locale.ROOT, "noise chunk build: round %d: %.1f us per NoiseChunk; router hashCode %.0f ns per field; mapAll + equals %.0f ns per field (%d %d)",
                round, build / 1000.0 / chunks, hashing / (double) reps / fields.length, equality / (double) (reps / 10) / fields.length, hash & 1, equal));
        }
    }
}
