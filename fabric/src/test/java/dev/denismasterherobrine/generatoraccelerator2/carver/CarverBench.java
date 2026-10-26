package dev.denismasterherobrine.generatoraccelerator2.carver;

import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.GenerationStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * {@code gradlew test --tests *CarverBench -Dga2.bench=true}: median time per chunk of the carver step
 * body on real terrain, vanilla vs GA2. Sites form a contiguous block so neighbouring chunks share
 * carver starts, as during generation. The GA2 path cache is cleared before each GA2 round.
 */
@EnabledIfSystemProperty(named = "ga2.bench", matches = "true")
public class CarverBench {
    @Test
    void carvers() {
        for (TerrainHarness.Preset preset : List.of(TerrainHarness.Preset.OVERWORLD, TerrainHarness.Preset.NETHER)) {
            List<TerrainHarness.Site> sites = new ArrayList<>();
            for (int x = 0; x < 6; x++) {
                for (int z = 0; z < 6; z++) {
                    sites.add(TerrainHarness.site(preset, 1234567L, 40 + x, -20 + z));
                }
            }
            int rounds = Integer.getInteger("ga2.bench.rounds", 7);
            long[] vanilla = new long[rounds];
            long[] ga2 = new long[rounds];
            for (int round = 0; round < rounds + 2; round++) {
                for (int setup = 0; setup < 2; setup++) {
                    List<ProtoChunk> copies = new ArrayList<>();
                    for (TerrainHarness.Site site : sites) {
                        ProtoChunk c = site.center();
                        TerrainHarness.vanillaSurface(site, c);
                        c.setPersistedStatus(ChunkStatus.SURFACE);
                        copies.add(c);
                    }
                    CarverPaths.clear();
                    Map<Long, ProtoChunk> neighbours = new HashMap<>();
                    // as in a level, every chunk's carver biome is computed once, not per carving run
                    for (TerrainHarness.Site site : sites) {
                        TerrainHarness.ChunkLookup warm = TerrainHarness.carverNeighbourhood(site.world, copies.get(sites.indexOf(site)), neighbours);
                        for (int dx = -8; dx <= 8; dx++) {
                            for (int dz = -8; dz <= 8; dz++) {
                                int cx = site.cx + dx;
                                int cz = site.cz + dz;
                                warm.get(cx, cz).carverBiome(() -> site.world.generator.getBiomeGenerationSettings(site.world.biomeSource.getNoiseBiome(
                                    QuartPos.fromBlock(cx << 4), 0, QuartPos.fromBlock(cz << 4), site.world.randomState.sampler())));
                            }
                        }
                    }
                    long t0 = System.nanoTime();
                    for (int i = 0; i < sites.size(); i++) {
                        TerrainHarness.World w = sites.get(i).world;
                        ProtoChunk chunk = copies.get(i);
                        TerrainHarness.ChunkLookup lookup = TerrainHarness.carverNeighbourhood(w, chunk, neighbours);
                        if (setup == 0) {
                            TerrainHarness.vanillaCarvers(w, chunk, lookup);
                        } else {
                            CarverRunner.apply(w.generator, lookup::get, w.registries.access, w.seed, w.randomState, new BiomeManager((x, y, z) -> null, w.zoomSeed),
                                chunk, GenerationStep.Carving.AIR);
                        }
                    }
                    long dt = (System.nanoTime() - t0) / sites.size();
                    if (round >= 2) {
                        (setup == 0 ? vanilla : ga2)[round - 2] = dt;
                    }
                }
            }
            long v = median(vanilla);
            long f = median(ga2);
            System.out.printf("CarverBench %s (%d chunks, median of %d): vanilla %.1f us/chunk, GA2 %.1f us/chunk, %.2fx%n", preset, sites.size(), rounds,
                v / 1000.0, f / 1000.0, (double) v / f);
        }
    }

    private static long median(long[] v) {
        long[] c = v.clone();
        Arrays.sort(c);
        return c[c.length / 2];
    }
}
