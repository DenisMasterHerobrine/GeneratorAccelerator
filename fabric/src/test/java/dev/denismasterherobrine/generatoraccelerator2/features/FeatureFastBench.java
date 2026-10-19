package dev.denismasterherobrine.generatoraccelerator2.features;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.Feature;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Time per placement, vanilla against the fast path, for the overworld's configured ores or trees (opt-in:
 * {@code gradlew test --tests "*FeatureFastBench*" -Dga2.bench=ore|tree}). Each round places every feature at 64 origins on a
 * fresh copy of the terrain. Ore origins are at heights through the stone. Tree origins are on the surface of the centre
 * chunk. The copy is not timed.
 */
public class FeatureFastBench {
    @Test
    void bench() {
        String kind = System.getProperty("ga2.bench", "");
        Assumptions.assumeTrue(kind.equals("ore") || kind.equals("tree"), "opt-in: -Dga2.bench=ore|tree");
        boolean trees = kind.equals("tree");
        HarnessWorld.Registries2 r = HarnessWorld.registries();
        List<FeatureRun.ConfiguredCase> cases = new ArrayList<>();
        for (FeatureRun.ConfiguredCase c : FeatureRun.configuredCases(r)) {
            Feature<?> f = c.feature().value().feature();
            if (c.originKind() == 0 && c.kind() == HarnessWorld.Kind.OVERWORLD
                && (trees ? f == Feature.TREE && !FeatureRun.hasBeehiveDecorator(c.feature().value()) : f == Feature.ORE)) {
                cases.add(c);
            }
        }
        cases.sort(java.util.Comparator.comparing(FeatureRun.ConfiguredCase::name));
        int rounds = Integer.getInteger("ga2.bench.rounds", 12);
        for (int round = 0; round < rounds; round++) {
            long vanilla = time(r, cases, true, trees);
            long fast = time(r, cases, false, trees);
            int n = cases.size() * 64;
            System.out.printf(java.util.Locale.ROOT, "FeatureFastBench %s round %d: vanilla %.0f ns, fast %.0f ns per placement (%.2fx)%n", kind, round,
                (double) vanilla / n, (double) fast / n, (double) vanilla / fast);
        }
    }

    private static long time(HarnessWorld.Registries2 r, List<FeatureRun.ConfiguredCase> cases, boolean vanilla, boolean trees) {
        long total = 0;
        LevelHeightAccessor heights = HarnessWorld.heights(r, HarnessWorld.Kind.OVERWORLD);
        NoiseBasedChunkGenerator generator = HarnessWorld.generator(r, HarnessWorld.Kind.OVERWORLD);
        Holder<Biome> biome = r.biomes.getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS);
        for (FeatureRun.ConfiguredCase c : cases) {
            ProtoChunk[] chunks = HarnessWorld.clone(r, FeatureRun.terrain(r, c.kind(), 20261002L), heights);
            for (ProtoChunk chunk : chunks) {
                chunk.fillBiomesFromNoise((x, y, z, sampler) -> biome, Climate.empty());
            }
            HarnessLevel level = new HarnessLevel(chunks, HarnessWorld.RADIUS, FeatureRun.CENTER_X, FeatureRun.CENTER_Z, heights,
                HarnessWorld.dimension(r, c.kind()), 20261002L, HarnessWorld.randomState(r, c.kind(), 20261002L), r.access, false);
            WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
            int[] ys = new int[64];
            for (int t = 0; t < 64; t++) {
                int x = FeatureRun.CENTER_X * 16 + (t * 7) % 16;
                int z = FeatureRun.CENTER_Z * 16 + (t * 11 + 3) % 16;
                ys[t] = trees ? level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) : -60 + (t * 37) % 120;
            }
            long[] took = new long[1];
            Runnable body = () -> {
                long t0 = System.nanoTime();
                for (int t = 0; t < 64; t++) {
                    random.setSeed(t * 0x9E3779B97F4A7C15L + c.name().hashCode());
                    int x = FeatureRun.CENTER_X * 16 + (t * 7) % 16;
                    int z = FeatureRun.CENTER_Z * 16 + (t * 11 + 3) % 16;
                    try {
                        c.feature().value().place(level, generator, random, new BlockPos(x, ys[t], z));
                    } catch (RuntimeException e) {
                        // vanilla's cocoa decorator throws on a tree without logs. Both sides throw alike
                    }
                }
                took[0] = System.nanoTime() - t0;
            };
            if (vanilla) {
                FeatureHooks.runVanilla(body);
            } else {
                body.run();
            }
            total += took[0];
        }
        return total;
    }
}
