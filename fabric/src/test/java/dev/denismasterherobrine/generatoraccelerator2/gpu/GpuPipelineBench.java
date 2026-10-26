package dev.denismasterherobrine.generatoraccelerator2.gpu;

import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.Util;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.lang.management.ManagementFactory;

/**
 * Multi-threaded BIOMES, NOISE and SURFACE over regions of R x R chunks. SURFACE runs on each region's interior, which has all
 * its neighbours. GA2's CPU steps are measured against the GPU path. There the GPU decides and packs biomes and blocks, and the
 * CPU rebuilds the chunks from that output and runs the surface step. The GPU path is pipelined: a producer thread runs region
 * k+1 on the GPU while the workers handle region k. Warm-up first, then alternating CPU and GPU measurements at fresh positions,
 * for each worker count.
 * {@code gradlew test --tests "*GpuPipelineBench*" -Dga2.gpu.pipeline=true --no-configuration-cache
 * [-Dga2.gpu.threadList=8,15 -Dga2.gpu.region=16 -Dga2.gpu.regions=4 -Dga2.gpu.pairs=3 -Dga2.gpu.f32=true]}.
 * {@code -Dga2.gpu.cpuScaling=true} runs the CPU path alone by thread count.
 */
public class GpuPipelineBench {
    private static void log(String fmt, Object... args) {
        System.out.println("[GpuPipeline] " + String.format(Locale.ROOT, fmt, args));
    }

    /** CPU-only BIOMES+NOISE+SURFACE throughput by thread count, with GC time and the executor the steps hand their work to. */
    @Test
    @EnabledIfSystemProperty(named = "ga2.gpu.cpuScaling", matches = "true")
    void cpuScaling() throws Exception {
        GpuProbe.Setup s = GpuDatapack.setup(Long.getLong("ga2.gpu.seed", 1234567L));
        TerrainHarness.World w = s.world;
        log("background executor: %s; available processors %d; max heap %d MB", Util.backgroundExecutor(), Runtime.getRuntime().availableProcessors(),
            Runtime.getRuntime().maxMemory() >> 20);
        int region = 16;
        int origin = 40000;
        for (int threads : new int[] { 1, 4, 8, 15, 15, 8, 4, 1 }) {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                long gc0 = gcMillis();
                long a = System.nanoTime();
                cpuRegion(w, pool, origin, 50000, region, new long[6]);
                long b = System.nanoTime();
                origin += region + 2;
                log("%2d threads: %.0f chunks/s through BIOMES+NOISE+SURFACE phases (%.1f ms wall per %dx%d region), GC %d ms", threads,
                    region * region / ((b - a) / 1e9), (b - a) / 1e6, region, region, gcMillis() - gc0);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private static long gcMillis() {
        long t = 0;
        for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            t += Math.max(0, gc.getCollectionTime());
        }
        return t;
    }

    @Test
    @EnabledIfSystemProperty(named = "ga2.gpu.pipeline", matches = "true")
    void pipeline() throws Exception {
        GpuProbe.Setup s = GpuDatapack.setup(Long.getLong("ga2.gpu.seed", 1234567L));
        int region = Integer.getInteger("ga2.gpu.region", 16);
        int regions = Integer.getInteger("ga2.gpu.regions", 4);
        int pairs = Integer.getInteger("ga2.gpu.pairs", 3);
        String[] threadList = System.getProperty("ga2.gpu.threadList", "8,15").split(",");
        try (Cl cl = Cl.open()) {
            boolean fp64 = cl.hasFp64() && !Boolean.getBoolean("ga2.gpu.f32");
            GpuProbe.Variant v = new GpuProbe.Variant(cl, s, fp64 ? "pipe-f64" : "pipe-f32", s.gen(fp64 ? ClGen.Prec.F64 : ClGen.Prec.F32, true), "-D FULL");
            log("device %s, %s (built in %.0f ms); regions of %dx%d chunks (%d interior get a surface), %d regions per measurement", cl.describe(), v.name, v.buildMs,
                region, region, (region - 2) * (region - 2), regions);
            int[] origin = { 20000 };
            for (String t : threadList) {
                int threads = Integer.parseInt(t.trim());
                ExecutorService pool = Executors.newFixedThreadPool(threads);
                try {
                    for (int warm = 0; warm < 2; warm++) {
                        cpuRun(s, pool, origin, region, regions);
                        gpuRun(s, cl, v, pool, origin, region, regions);
                    }
                    List<double[]> cpu = new ArrayList<>();
                    List<double[]> gpu = new ArrayList<>();
                    for (int p = 0; p < pairs; p++) {
                        cpu.add(cpuRun(s, pool, origin, region, regions));
                        gpu.add(gpuRun(s, cl, v, pool, origin, region, regions));
                    }
                    report(threads, "CPU", cpu, region, regions);
                    report(threads, "GPU", gpu, region, regions);
                } finally {
                    pool.shutdownNow();
                }
            }
            v.close();
        }
    }

    /**
     * @param rs per measurement: {wall ns, worker ns in biomes+noise (CPU) or rebuild (GPU), worker ns making NoiseChunks at SURFACE,
     *           worker ns in buildSurface, GPU producer ns (kernels + readback), ns the workers waited for the GPU}
     */
    private static void report(int threads, String name, List<double[]> rs, int region, int regions) {
        double[] m = new double[6];
        for (double[] r : rs) {
            for (int i = 0; i < 6; i++) {
                m[i] += r[i] / rs.size();
            }
        }
        int chunks = region * region * regions;
        int interior = (region - 2) * (region - 2) * regions;
        double wallS = m[0] / 1e9;
        log("%2d threads %s: %.0f chunks/s through BIOMES+NOISE and %.0f interior chunks/s through SURFACE (%.1f ms for %d regions); worker time per chunk:"
                + " %s %.0f us, NoiseChunk for SURFACE %.0f us, buildSurface %.0f us; GPU producer %.0f us/chunk, workers waiting on it %.1f ms",
            threads, name, chunks / wallS, interior / wallS, m[0] / 1e6, regions, name.equals("CPU") ? "biomes+noise" : "rebuild", m[1] / 1e3 / chunks,
            m[2] / 1e3 / interior, m[3] / 1e3 / interior, m[4] / 1e3 / chunks, m[5] / 1e6);
    }

    private static double[] cpuRun(GpuProbe.Setup s, ExecutorService pool, int[] origin, int region, int regions) throws Exception {
        long[] acc = new long[6];
        long a = System.nanoTime();
        for (int r = 0; r < regions; r++) {
            cpuRegion(s.world, pool, origin[0], 30000, region, acc);
            origin[0] += region + 2;
        }
        acc[0] = System.nanoTime() - a;
        return toDouble(acc);
    }

    private record Result(GpuProbe.Run run, GpuProbe.Packed packed, int ox) {}

    private static double[] gpuRun(GpuProbe.Setup s, Cl cl, GpuProbe.Variant v, ExecutorService pool, int[] origin, int region, int regions) throws Exception {
        long[] acc = new long[6];
        int[] ox = new int[regions];
        for (int r = 0; r < regions; r++) {
            ox[r] = origin[0];
            origin[0] += region + 2;
        }
        BlockingQueue<Object> queue = new ArrayBlockingQueue<>(2);
        AtomicLong producer = new AtomicLong();
        Thread gpuThread = new Thread(() -> {
            try {
                for (int r = 0; r < regions; r++) {
                    long t0 = System.nanoTime();
                    GpuProbe.Run run = new GpuProbe.Run(cl, v, ox[r], 30000, region, region, false);
                    GpuProbe.Packed packed;
                    try {
                        packed = run.readPacked(null);
                    } finally {
                        run.close();
                    }
                    producer.addAndGet(System.nanoTime() - t0);
                    queue.put(new Result(run, packed, ox[r]));
                }
            } catch (Throwable t) {
                try {
                    queue.put(t);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "gpu-producer");
        long a = System.nanoTime();
        gpuThread.start();
        long waited = 0;
        for (int r = 0; r < regions; r++) {
            long w0 = System.nanoTime();
            Object o = queue.take();
            waited += System.nanoTime() - w0;
            if (o instanceof Throwable t) {
                throw new RuntimeException(t);
            }
            Result res = (Result) o;
            ProtoChunk[] grid = new ProtoChunk[region * region];
            AtomicLong rebuild = new AtomicLong();
            runAll(pool, region * region, i -> {
                long st = System.nanoTime();
                ProtoChunk c = s.world.emptyChunk(res.ox() + i / region, 30000 + i % region);
                GpuProbe.consumePacked(s, c, res.packed(), res.run(), i / region, i % region);
                c.setPersistedStatus(ChunkStatus.NOISE);
                grid[i] = c;
                rebuild.addAndGet(System.nanoTime() - st);
            });
            acc[1] += rebuild.get();
            surfaces(s.world, pool, grid, region, res.ox(), 30000, acc);
        }
        acc[0] = System.nanoTime() - a;
        gpuThread.join();
        acc[4] = producer.get();
        acc[5] = waited;
        return toDouble(acc);
    }

    private static double[] toDouble(long[] a) {
        double[] d = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            d[i] = a[i];
        }
        return d;
    }

    private static void cpuRegion(TerrainHarness.World w, ExecutorService pool, int ox, int oz, int region, long[] acc) throws Exception {
        ProtoChunk[] grid = new ProtoChunk[region * region];
        AtomicLong t = new AtomicLong();
        runAll(pool, region * region, i -> {
            long st = System.nanoTime();
            ProtoChunk c = w.emptyChunk(ox + i / region, oz + i % region);
            w.noiseChunk(c);
            w.generator.createBiomes(w.randomState, Blender.empty(), null, c).join();
            c.setPersistedStatus(ChunkStatus.BIOMES);
            w.generator.fillFromNoise(Blender.empty(), w.randomState, null, c).join();
            c.setPersistedStatus(ChunkStatus.NOISE);
            grid[i] = c;
            t.addAndGet(System.nanoTime() - st);
        });
        acc[1] += t.get();
        surfaces(w, pool, grid, region, ox, oz, acc);
    }

    /** SURFACE on every interior chunk: biomes of the 3x3 around it through the level's biome zoom, as the step's region does. */
    private static void surfaces(TerrainHarness.World w, ExecutorService pool, ProtoChunk[] grid, int region, int ox, int oz, long[] acc) throws Exception {
        int side = region - 2;
        AtomicLong make = new AtomicLong();
        AtomicLong build = new AtomicLong();
        runAll(pool, side * side, k -> {
            int i = 1 + k / side;
            int j = 1 + k % side;
            ProtoChunk chunk = grid[i * region + j];
            long t0 = System.nanoTime();
            w.noiseChunk(chunk); // the step's NoiseChunk: made at BIOMES on the CPU path, here when the GPU filled the chunk
            long t1 = System.nanoTime();
            BiomeManager manager = new BiomeManager((qx, qy, qz) -> grid[((qx >> 2) - ox) * region + ((qz >> 2) - oz)].getNoiseBiome(qx, qy, qz), w.zoomSeed);
            w.generator.buildSurface(chunk, w.generationContext(), w.randomState, null, manager, w.registries.biomes, Blender.empty());
            long t2 = System.nanoTime();
            make.addAndGet(t1 - t0);
            build.addAndGet(t2 - t1);
        });
        acc[2] += make.get();
        acc[3] += build.get();
    }

    private interface Task {
        void run(int i) throws Exception;
    }

    private static void runAll(ExecutorService pool, int n, Task task) throws Exception {
        List<Future<?>> fs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            final int k = i;
            fs.add(pool.submit(() -> {
                task.run(k);
                return null;
            }));
        }
        for (Future<?> f : fs) {
            f.get();
        }
    }
}
