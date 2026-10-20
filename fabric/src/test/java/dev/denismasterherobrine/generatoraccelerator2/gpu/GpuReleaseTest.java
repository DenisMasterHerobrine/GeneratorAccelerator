package dev.denismasterherobrine.generatoraccelerator2.gpu;

import dev.denismasterherobrine.generatoraccelerator2.gpu.cl.ClDeviceInfo;
import dev.denismasterherobrine.generatoraccelerator2.gpu.cl.ClLoader;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.GpuDevice;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.GpuRuntime;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.GpuWorldgen;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * A closed world and a closed device give their VRAM back. Each round opens the device, builds a dimension's program and generates
 * chunks, then closes both. The GPU's used memory (nvidia-smi) must come back near where it started. The driver's other users move
 * it a little, so the check allows a margin.
 * {@code gradlew :fabric:test --tests "*GpuReleaseTest*" -Dga2.gpu=true --no-configuration-cache}
 */
@EnabledIfSystemProperty(named = "ga2.gpu", matches = "true")
public class GpuReleaseTest {
    private static final long MARGIN_MIB = 96;

    private static void log(String fmt, Object... args) {
        System.out.println("[GpuRelease] " + String.format(Locale.ROOT, fmt, args));
    }

    /** The first GPU's used memory in MiB, or -1 without nvidia-smi. */
    private static long usedMib() {
        try {
            Process p = new ProcessBuilder("nvidia-smi", "--query-gpu=memory.used", "--format=csv,noheader,nounits").redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = r.readLine();
                p.waitFor(10, TimeUnit.SECONDS);
                return line == null ? -1 : Long.parseLong(line.trim());
            }
        } catch (Exception e) {
            return -1;
        }
    }

    /** The lowest reading over a few seconds. The driver frees a released context with a short delay. */
    private static long settledMib() throws InterruptedException {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 10; i++) {
            Thread.sleep(300);
            best = Math.min(best, usedMib());
        }
        return best;
    }

    @Test
    void releasesVram() throws Exception {
        ClLoader.Loaded loaded = ClLoader.load(Path.of("build", "gpu-lib").toAbsolutePath());
        ClDeviceInfo info = loaded.api().devices(false).stream().filter(ClDeviceInfo::hasFp64).findFirst().orElseThrow();
        GpuProbe.Setup s = GpuDatapack.setup(1234567L);
        long base = settledMib();
        log("before: %d MiB", base);
        for (int round = 0; round < 3; round++) {
            GpuDevice d = GpuRuntime.open(loaded, info);
            GpuWorldgen wg = GpuRuntime.worldgen(d, "overworld", s.world.settings, s.world.randomState);
            GpuRuntime.prepare(wg, s.world.biomeSource);
            d.addPump(wg);
            GpuRuntime.ready(wg);
            List<CompletableFuture<GpuWorldgen.NoiseResult>> nf = new ArrayList<>();
            GpuWorldgen.Beard none = new GpuWorldgen.Beard(new int[0], new int[0]);
            for (int i = 0; i < 32; i++) {
                for (int j = 0; j < 32; j++) {
                    nf.add(wg.noise(new ChunkPos(round * 1000 + i, j), none));
                }
            }
            for (CompletableFuture<GpuWorldgen.NoiseResult> f : nf) {
                f.get(120, TimeUnit.SECONDS);
            }
            long open = usedMib();
            CompletableFuture<Void> worldClosed = new CompletableFuture<>();
            d.submit(() -> {
                GpuRuntime.close(wg);
                worldClosed.complete(null);
            });
            worldClosed.get(60, TimeUnit.SECONDS);
            // What the device holds with no world on it: the context and its queues.
            long context = settledMib();
            d.close();
            CompletableFuture<Void> closed = new CompletableFuture<>();
            d.submit(() -> closed.complete(null));
            closed.get(60, TimeUnit.SECONDS);
            long after = settledMib();
            log("round %d: %d MiB with the device open, %d MiB with the world closed, %d MiB after release (%+d against the start)", round, open, context,
                after, after - base);
            if (base >= 0 && after - base > MARGIN_MIB) {
                throw new AssertionError("round " + round + " kept " + (after - base) + " MiB after the device was released");
            }
        }
    }
}
