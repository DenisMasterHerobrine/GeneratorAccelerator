package dev.denismasterherobrine.generatoraccelerator2.light;

import dev.denismasterherobrine.generatoraccelerator2.gpu.cl.ClDeviceInfo;
import dev.denismasterherobrine.generatoraccelerator2.gpu.cl.ClLoader;
import dev.denismasterherobrine.generatoraccelerator2.gpu.light.GpuLight;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.GpuDevice;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.GpuRuntime;
import dev.denismasterherobrine.generatoraccelerator2.parallellight.ParallelLight;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The light thread's wall time per chunk for lighting new chunks (section status, light sources, propagation in batches of
 * {@code batch} chunks): the CPU alone (GA2's fast engine, no helpers), every batch's increases on the GPU, and GA2's choice per
 * batch. The same chunks in the same order each time; the modes take turns within every round, so a busy machine slows all
 * three alike. The first two rounds warm up. {@code gradlew test --tests "*GpuLightBench*" -Dga2.bench=true -Dga2.gpu=true}
 */
@EnabledIfSystemProperty(named = "ga2.bench", matches = "true")
public class GpuLightBench {
    private enum Mode { CPU, GPU, CHOICE }

    /** The terrain's noise and surface only, 5x5 chunks: no placed light sources, shapes or canopy. */
    private static ProtoChunk[] plain(TerrainHarness.Preset preset, long seed, int x0, int z0) {
        ProtoChunk[] chunks = new ProtoChunk[25];
        for (int dz = 0; dz < 5; dz++) {
            for (int dx = 0; dx < 5; dx++) {
                TerrainHarness.Site site = TerrainHarness.site(preset, seed, x0 + dx, z0 + dz);
                ProtoChunk chunk = site.center();
                TerrainHarness.vanillaSurface(site, chunk);
                chunks[dx + dz * 5] = chunk;
            }
        }
        return chunks;
    }

    @Test
    void light() throws Exception {
        ClLoader.Loaded loaded = ClLoader.load(Path.of("build", "gpu-lib").toAbsolutePath());
        ClDeviceInfo info = loaded.api().devices(false).stream().filter(ClDeviceInfo::hasFp64).findFirst().orElseThrow();
        GpuDevice device = GpuRuntime.open(loaded, info);
        System.out.println("GPU light bench on " + device.describe());
        int rounds = Integer.getInteger("ga2.bench.rounds", 12);
        int[] batches = System.getProperty("ga2.bench.batch") != null
            ? Arrays.stream(System.getProperty("ga2.bench.batch").split(",")).mapToInt(Integer::parseInt).toArray() : new int[] { 3, 12, 64 };
        Object[][] terrains = {
            { "overworld", TerrainHarness.Preset.OVERWORLD, 1234567L, 0, 0 },
            { "amplified", TerrainHarness.Preset.AMPLIFIED, -42L, 40, -7 },
            { "plain overworld", TerrainHarness.Preset.OVERWORLD, 1234567L, 0, 0 },
            // 15x15 full chunks of a vanilla world, with -Dga2.bench.regionFile=<.mca>
            { "real chunks", TerrainHarness.Preset.OVERWORLD, 1234567L, 8, 8 },
        };
        String only = System.getProperty("ga2.bench.terrain");
        ParallelLight.anyEngine = true;
        ParallelLight.helpersOverride = 0;
        GpuLight.testDevice = device;
        try {
            // the program builds in the background at the first offer
            long deadline = System.nanoTime() + 60_000_000_000L;
            while (!GpuLight.statusLines().get(0).startsWith("GPU light: on") && System.nanoTime() < deadline) {
                GpuLight.INSTANCE.wants(null, true, Integer.MAX_VALUE, 0);
                Thread.sleep(20);
            }
            for (Object[] t : terrains) {
                if (only != null && !only.equals(t[0])) {
                    continue;
                }
                TerrainHarness.World world = TerrainHarness.world((TerrainHarness.Preset) t[1], (Long) t[2]);
                ProtoChunk[] base;
                if (t[0].equals("real chunks")) {
                    String file = System.getProperty("ga2.bench.regionFile");
                    base = file == null ? null : dev.denismasterherobrine.generatoraccelerator2.testutil.RegionChunks.load(Path.of(file), (Integer) t[3], (Integer) t[4], 15, world);
                    if (base == null) {
                        continue;
                    }
                } else {
                    base = ((String) t[0]).startsWith("plain") ? plain((TerrainHarness.Preset) t[1], (Long) t[2], (Integer) t[3], (Integer) t[4])
                        : LightExactnessTest.terrain((TerrainHarness.Preset) t[1], (Long) t[2], (Integer) t[3], (Integer) t[4]);
                }
                for (int batch : batches) {
                    double[][] ms = new double[Mode.values().length][rounds];
                    double[][] parts = new double[Mode.values().length][3];
                    long[] gpuBatches = new long[Mode.values().length];
                    for (int round = 0; round < rounds; round++) {
                        for (Mode m : Mode.values()) {
                            ParallelLight.setIncreaseOffload(m == Mode.CPU ? null : GpuLight.INSTANCE);
                            GpuLight.testAlways = m == Mode.GPU;
                            long b0 = GpuLight.BATCHES.sum();
                            LightExactnessTest.Run run = LightExactnessTest.run(world, base, true, (Long) t[2], false, 11L, batch);
                            ms[m.ordinal()][round] = run.lightNanos / 1e6 / base.length;
                            if (round == rounds - 1) {
                                parts[m.ordinal()][0] = run.initNanos / 1e6 / base.length;
                                parts[m.ordinal()][1] = run.sourcesNanos / 1e6 / base.length;
                                parts[m.ordinal()][2] = run.updatesNanos / 1e6 / base.length;
                                gpuBatches[m.ordinal()] = GpuLight.BATCHES.sum() - b0;
                            }
                        }
                    }
                    StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "GPU light bench: %s, %d chunks, batches of %d chunks, light thread ms per chunk"
                        + " (median and best of rounds 3-%d):", t[0], base.length, batch, rounds));
                    for (Mode m : Mode.values()) {
                        double[] v = Arrays.copyOfRange(ms[m.ordinal()], 2, rounds);
                        Arrays.sort(v);
                        sb.append(String.format(Locale.ROOT, "%n    %-6s median %.3f, best %.3f (last round: init %.3f, sources %.3f, updates %.3f; %d batches on the GPU)",
                            m, v[v.length / 2], v[0], parts[m.ordinal()][0], parts[m.ordinal()][1], parts[m.ordinal()][2], gpuBatches[m.ordinal()]));
                    }
                    System.out.println(sb);
                }
            }
            for (String line : GpuLight.statusLines()) {
                System.out.println(line);
            }
        } finally {
            GpuLight.testAlways = false;
            GpuLight.testDevice = null;
            ParallelLight.anyEngine = false;
            ParallelLight.helpersOverride = -1;
            ParallelLight.setIncreaseOffload(null);
        }
    }
}
