package dev.denismasterherobrine.generatoraccelerator2.testutil;

import dev.denismasterherobrine.generatoraccelerator2.aquifer.AquiferFastPath;
import dev.denismasterherobrine.generatoraccelerator2.aquifer.SurfaceLevelCache;
import dev.denismasterherobrine.generatoraccelerator2.beardifier.BeardifierFastPath;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.DfcHooks;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import net.minecraft.core.HolderGetter;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Times doFill's loop over a grid of chunks in several setups. Each round uses a fresh RandomState
 * so no cross-chunk cache carries over between rounds. Rounds rotate between setups, and the median
 * per chunk is reported.
 */
public final class FillBench {
    public enum Setup {
        /** Everything vanilla. */
        VANILLA(false, false, false, false),
        /** Compiled noise path, aquifer and beardifier vanilla. */
        COMPILED(true, false, false, false),
        /** Compiled noise path plus the aquifer (with shared surface levels) and beardifier fast paths. */
        COMPILED_FAST(true, true, true, true),
        /** As COMPILED_FAST, but every chunk computes its own surface levels. */
        COMPILED_FAST_PRIVATE_SURFACE(true, true, false, true),
        /** NoiseChunk hooks off (compat setup), aquifer and beardifier vanilla. */
        HOOKS_OFF(false, false, false, false),
        /** NoiseChunk hooks off plus both fast paths. */
        HOOKS_OFF_FAST(false, true, true, true);

        final boolean compiled;
        final boolean aquifer;
        final boolean sharedSurface;
        final boolean beardifier;

        Setup(boolean compiled, boolean aquifer, boolean sharedSurface, boolean beardifier) {
            this.compiled = compiled;
            this.aquifer = aquifer;
            this.sharedSurface = sharedSurface;
            this.beardifier = beardifier;
        }
    }

    private FillBench() {}

    /**
     * @param beards per chunk index, the beardifier to use (a fresh instance each call)
     * @return median ns per chunk for each setup
     */
    public static Map<Setup, Long> run(NoiseGeneratorSettings settings, HolderGetter<NormalNoise.NoiseParameters> noises, long seed, int[][] chunks,
                                       IntFunction<DensityFunctions.BeardifierOrMarker> beards, List<Setup> setups, int warmup, int rounds) {
        Map<Setup, List<Long>> times = new LinkedHashMap<>();
        for (Setup s : setups) {
            times.put(s, new ArrayList<>());
        }
        for (int round = 0; round < warmup + rounds; round++) {
            for (Setup setup : setups) {
                long ns = round(setup, settings, noises, seed, chunks, beards);
                if (round >= warmup) {
                    times.get(setup).add(ns / chunks.length);
                }
            }
        }
        Map<Setup, Long> medians = new LinkedHashMap<>();
        for (Map.Entry<Setup, List<Long>> e : times.entrySet()) {
            long[] v = e.getValue().stream().mapToLong(Long::longValue).toArray();
            Arrays.sort(v);
            medians.put(e.getKey(), v[v.length / 2]);
        }
        return medians;
    }

    private static long round(Setup setup, NoiseGeneratorSettings settings, HolderGetter<NormalNoise.NoiseParameters> noises, long seed, int[][] chunks,
                              IntFunction<DensityFunctions.BeardifierOrMarker> beards) {
        RandomState state = RandomState.create(settings, noises, seed);
        long total = 0;
        for (int i = 0; i < chunks.length; i++) {
            int cx = chunks[i][0];
            int cz = chunks[i][1];
            int index = i;
            long t0 = System.nanoTime();
            if (setup == Setup.VANILLA) {
                DfcHooks.runVanilla(() -> FillHarness.fill(FillHarness.chunk(state, settings, cx, cz, beards.apply(index), FillHarness.vanillaPicker(settings)),
                    settings, cx, cz, false));
            } else {
                AquiferFastPath.override = setup.aquifer;
                SurfaceLevelCache.override = setup.sharedSurface;
                BeardifierFastPath.override = setup.beardifier;
                NoiseChunk chunk;
                try {
                    chunk = FillHarness.chunk(state, settings, cx, cz, beards.apply(index), FillHarness.vanillaPicker(settings));
                } finally {
                    AquiferFastPath.override = null;
                    SurfaceLevelCache.override = null;
                    BeardifierFastPath.override = null;
                }
                if (setup.compiled) {
                    FillHarness.fill(chunk, settings, cx, cz, false);
                } else {
                    DfcHooks.runVanilla(() -> FillHarness.fill(chunk, settings, cx, cz, false));
                }
            }
            total += System.nanoTime() - t0;
        }
        return total;
    }

    public static String format(Map<Setup, Long> medians) {
        StringBuilder sb = new StringBuilder();
        long base = medians.getOrDefault(Setup.VANILLA, 0L);
        for (Map.Entry<Setup, Long> e : medians.entrySet()) {
            sb.append(String.format("  %-30s %8.3f ms/chunk", e.getKey(), e.getValue() / 1.0e6));
            if (base > 0) {
                sb.append(String.format("  (%.2fx vs vanilla)", base / (double) e.getValue()));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** All setups, or the comma-separated list in {@code -Dga2.bench.setups}. */
    public static List<Setup> setups() {
        String only = System.getProperty("ga2.bench.setups");
        if (only == null || only.isBlank()) {
            return List.of(Setup.values());
        }
        List<Setup> out = new ArrayList<>();
        for (String s : only.split(",")) {
            out.add(Setup.valueOf(s.trim()));
        }
        return out;
    }

    public static int[][] grid(int x0, int z0, int size) {
        int[][] out = new int[size * size][];
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                out[i * size + j] = new int[] { x0 + i, z0 + j };
            }
        }
        return out;
    }
}
