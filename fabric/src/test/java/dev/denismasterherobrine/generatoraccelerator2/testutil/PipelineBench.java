package dev.denismasterherobrine.generatoraccelerator2.testutil;

import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.FillHeights;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.LazyInterpolation;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.MapAllMemo;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Single-threaded CPU time per chunk of the BIOMES and NOISE steps (the bulk of worldgen CPU) over fresh chunk
 * positions, with GA2's switchable noise-step changes on and off, so changes of a few percent can be measured
 * without the noise of a server run. {@code gradlew test --tests "*PipelineBench*" -Dga2.bench=true}
 */
@EnabledIfSystemProperty(named = "ga2.bench", matches = "true")
public class PipelineBench {
    private static final int SIDE = 8;

    @Test
    void biomesAndNoise() {
        TerrainHarness.World world = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L);
        int rounds = Integer.getInteger("ga2.bench.rounds", 12);
        String[] setups = { "all on", "memo off", "lazy interpolation off", "column heightmaps off", "fast fill off", "sampler columns off" };
        List<List<long[]>> times = new ArrayList<>();
        for (int s = 0; s < setups.length; s++) {
            times.add(new ArrayList<>());
        }
        int origin = 0;
        // -Dga2.bench.setups=0,4 runs only those setups (indexes into the list above)
        java.util.Set<Integer> only = new java.util.HashSet<>();
        for (String part : System.getProperty("ga2.bench.setups", "").split(",")) {
            if (!part.isBlank()) {
                only.add(Integer.parseInt(part.trim()));
            }
        }
        for (int r = -3; r < rounds; r++) {
            for (int s = 0; s < setups.length; s++) {
                if (!only.isEmpty() && !only.contains(s)) {
                    continue;
                }
                set(s);
                try {
                    long[] t = run(world, origin);
                    origin += SIDE + 2;
                    if (r >= 0) {
                        times.get(s).add(t);
                    }
                } finally {
                    set(0);
                }
            }
        }
        int chunks = SIDE * SIDE;
        System.out.println("[PipelineBench] switches: " + System.getProperties().stringPropertyNames().stream().filter(p -> p.startsWith("generatoraccelerator2.")).map(p -> p + "=" + System.getProperty(p)).sorted().toList());
        for (int s = 0; s < setups.length; s++) {
            if (times.get(s).isEmpty()) {
                continue;
            }
            long[] biomes = times.get(s).stream().mapToLong(t -> t[0]).sorted().toArray();
            long[] noise = times.get(s).stream().mapToLong(t -> t[1]).sorted().toArray();
            System.out.printf(Locale.ROOT, "[PipelineBench] %-24s biomes %7.1f us/chunk, noise %7.1f us/chunk (median of %d rounds)%n", setups[s],
                biomes[biomes.length / 2] / 1000.0 / chunks, noise[noise.length / 2] / 1000.0 / chunks, biomes.length);
        }
    }

    private static void set(int setup) {
        MapAllMemo.setEnabled(setup != 1);
        LazyInterpolation.ENABLED = setup != 2;
        FillHeights.ENABLED = setup != 3;
        dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.FastFill.ENABLED = setup != 4;
        dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.BoundSampler.COLUMNS = setup != 5;
    }

    /** @return nanoseconds spent in BIOMES and NOISE over a SIDE x SIDE grid of fresh chunks */
    private static long[] run(TerrainHarness.World world, int origin) {
        ProtoChunk[] chunks = new ProtoChunk[SIDE * SIDE];
        long t0 = System.nanoTime();
        for (int i = 0; i < chunks.length; i++) {
            ProtoChunk chunk = world.emptyChunk(origin + i % SIDE, 3000 + i / SIDE);
            world.noiseChunk(chunk);
            world.generator.createBiomes(world.randomState, Blender.empty(), null, chunk).join();
            chunk.setPersistedStatus(ChunkStatus.BIOMES);
            chunks[i] = chunk;
        }
        long t1 = System.nanoTime();
        for (ProtoChunk chunk : chunks) {
            world.generator.fillFromNoise(Blender.empty(), world.randomState, null, chunk).join();
        }
        long t2 = System.nanoTime();
        return new long[] { t1 - t0, t2 - t1 };
    }

}
