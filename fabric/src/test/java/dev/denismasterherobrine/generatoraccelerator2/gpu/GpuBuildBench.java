package dev.denismasterherobrine.generatoraccelerator2.gpu;

import dev.denismasterherobrine.generatoraccelerator2.gpu.cl.ClDeviceInfo;
import dev.denismasterherobrine.generatoraccelerator2.gpu.cl.ClLoader;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.GpuDevice;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.GpuRuntime;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.GpuWorldgen;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.ProgramParts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The production program (surface included, as in game) built in parts ({@code GpuWorldgen.parts}): cold build times, idle and
 * under load, of the whole program, its parts and each kernel alone, and device time whole against in parts. Every timed build gets
 * a new {@code GA2_NONCE}, so the driver's cache never answers. {@code gradlew :fabric:test --tests "*GpuBuildBench.<test>"
 * -Dga2.gpu.build=true --no-configuration-cache [-Dga2.gpu.datapack=<dir>] [-Dga2.gpu.sources=<dir of .cl files>]}. The
 * {@code deviceTime} test also needs {@code -Dgeneratoraccelerator2.gpu.profile=true}, and with
 * {@code -Dgeneratoraccelerator2.gpu.sideQueue=false -Dgeneratoraccelerator2.gpu.regionQueue=false} no kernels overlap.
 */
@EnabledIfSystemProperty(named = "ga2.gpu.build", matches = "true")
public class GpuBuildBench {
    private static void log(String fmt, Object... args) {
        System.out.println("[GpuBuild] " + String.format(Locale.ROOT, fmt, args));
    }

    private static final java.util.concurrent.atomic.AtomicLong NONCE = new java.util.concurrent.atomic.AtomicLong(System.nanoTime() & 0x3fffffffL);

    private static GpuDevice device() throws Exception {
        ClLoader.Loaded loaded = ClLoader.load(Path.of("build", "gpu-lib").toAbsolutePath());
        ClDeviceInfo info = loaded.api().devices(false).stream().filter(ClDeviceInfo::hasFp64).findFirst().orElseThrow();
        GpuDevice d = GpuRuntime.open(loaded, info);
        log("device: %s", d.describe());
        return d;
    }

    /** The production program's source, written by the build's dump. */
    private static List<Path> sources(GpuDevice d) throws Exception {
        String given = System.getProperty("ga2.gpu.sources");
        Path dir = given != null ? Path.of(given) : Path.of("build", "gpu-build", "src-" + NONCE.get()).toAbsolutePath();
        if (given == null) {
            GpuProbe.Setup s = GpuDatapack.setup(Long.getLong("ga2.gpu.seed", 1234567L));
            System.setProperty("generatoraccelerator2.gpu.dumpSource", dir.toString());
            try {
                GpuWorldgen wg = GpuRuntime.worldgen(d, "build-bench", s.world.settings, s.world.randomState);
                wg.buildDefines = " -D GA2_NONCE=" + NONCE.getAndIncrement();
                wg.surfaceInputs(s.world.generator, s.world.seed);
                wg.fluidPicker = s.world.fluidPicker;
                wg.levelHeights = s.world.heights;
                GpuRuntime.prepare(wg, s.world.biomeSource);
                GpuRuntime.ready(wg);
                log("production build: %s", wg.status());
                wg.prepareSurface();
                log("production build, surface taken: %s", wg.status());
                GpuRuntime.close(wg);
            } finally {
                System.clearProperty("generatoraccelerator2.gpu.dumpSource");
            }
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".cl")).sorted().toList();
        }
    }

    private static double coldBuild(GpuDevice d, String src) {
        String options = Gpu.BUILD_OPTIONS + " -D GA2_NONCE=" + NONCE.getAndIncrement();
        long t0 = System.nanoTime();
        long p = d.cl.buildProgram(d.context, d.info.device(), src, options, new StringBuilder());
        double s = (System.nanoTime() - t0) / 1e9;
        d.cl.releaseProgram(p);
        return s;
    }

    /**
     * The program's parts ({@code GpuWorldgen.parts}) built cold four ways: the whole program here, the parts here one after another,
     * the parts here on threads of their own, and the parts in processes of their own ({@code ProgramCache.start}).
     */
    @Test
    void parts() throws Exception {
        GpuDevice d = device();
        for (Path file : sources(d)) {
            String src = Files.readString(file);
            List<List<String>> parts = GpuWorldgen.parts(ProgramParts.names(src));
            List<String> sources = parts.stream().map(p -> ProgramParts.only(src, p)).toList();
            log("%s: %,d chars, %d parts", file.getFileName(), src.length(), parts.size());
            log("  whole program here: %.1f s", coldBuild(d, src));
            double sum = 0;
            for (int i = 0; i < sources.size(); i++) {
                double s = coldBuild(d, sources.get(i));
                sum += s;
                log("  part %d alone here: %5.1f s  %s", i, s, parts.get(i).size() > 6 ? parts.get(i).size() + " kernels" : parts.get(i));
            }
            log("  parts here, one after another: %.1f s", sum);
            long t0 = System.nanoTime();
            List<Thread> threads = new ArrayList<>();
            for (String part : sources) {
                threads.add(Thread.ofPlatform().start(() -> coldBuild(d, part)));
            }
            for (Thread t : threads) {
                t.join();
            }
            log("  parts here, on threads of their own: %.1f s", (System.nanoTime() - t0) / 1e9);
            System.setProperty("generatoraccelerator2.gpu.noBinaryCache", "true");
            try {
                String options = Gpu.BUILD_OPTIONS + " -D GA2_NONCE=" + NONCE.getAndIncrement();
                t0 = System.nanoTime();
                var b = dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.ProgramCache.start(d, sources, options);
                StringBuilder how = new StringBuilder();
                for (int i = 0; i < b.size(); i++) {
                    d.cl.releaseProgram(b.take(i, new StringBuilder()));
                    how.append(String.format(Locale.ROOT, " %d: %.1f s (%s)", i, b.readyMs[i] / 1e3, b.how[i]));
                }
                log("  parts in processes of their own: %.1f s;%s", (System.nanoTime() - t0) / 1e9, how);
            } finally {
                System.clearProperty("generatoraccelerator2.gpu.noBinaryCache");
            }
        }
    }

    /**
     * As in game while chunks generate: every logical processor busy at normal priority ({@code -Dga2.gpu.load=<threads>}). The whole
     * program built here on a thread at the prepare thread's priority, against the parts in processes of their own. Twice, alternating.
     */
    @Test
    void underLoad() throws Exception {
        GpuDevice d = device();
        int n = Integer.getInteger("ga2.gpu.load", Runtime.getRuntime().availableProcessors());
        for (Path file : sources(d)) {
            String src = Files.readString(file);
            List<String> sources = GpuWorldgen.parts(ProgramParts.names(src)).stream().map(p -> ProgramParts.only(src, p)).toList();
            java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
            List<Thread> load = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                load.add(Thread.ofPlatform().daemon().start(() -> {
                    double x = 1;
                    while (!stop.get()) {
                        x = Math.sin(x) + 1.0001;
                    }
                }));
            }
            try {
                log("%s under load (%d busy threads):", file.getFileName(), n);
                for (int round = 0; round < 2; round++) {
                    double[] whole = new double[1];
                    Thread t = new Thread(() -> whole[0] = coldBuild(d, src));
                    t.setPriority(Thread.NORM_PRIORITY + 3);
                    t.start();
                    t.join();
                    log("  whole program here at priority 8: %.1f s", whole[0]);
                    System.setProperty("generatoraccelerator2.gpu.noBinaryCache", "true");
                    try {
                        String options = Gpu.BUILD_OPTIONS + " -D GA2_NONCE=" + NONCE.getAndIncrement();
                        var b = dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.ProgramCache.start(d, sources, options);
                        StringBuilder how = new StringBuilder();
                        double required = 0;
                        for (int i = 0; i < b.size(); i++) {
                            d.cl.releaseProgram(b.take(i, new StringBuilder()));
                            how.append(String.format(Locale.ROOT, " %d: %.1f s", i, b.readyMs[i] / 1e3));
                            required = i < b.size() - 1 ? Math.max(required, b.readyMs[i]) : required;
                        }
                        log("  parts in processes: all but the surface %.1f s, surface %.1f s;%s", required / 1e3, b.readyMs[b.size() - 1] / 1e3, how);
                    } finally {
                        System.clearProperty("generatoraccelerator2.gpu.noBinaryCache");
                    }
                }
            } finally {
                stop.set(true);
                for (Thread t : load) {
                    t.join();
                }
            }
        }
    }

    /**
     * Device time per kernel, the program built whole against built in parts ({@code -Dgeneratoraccelerator2.gpu.profile=true}). The
     * two dimensions take turns on the same chunks: noise, biomes and the surface of {@code ga2.gpu.square}² chunks per round.
     */
    @Test
    void deviceTime() throws Exception {
        if (!Boolean.getBoolean("generatoraccelerator2.gpu.profile")) {
            throw new IllegalStateException("needs -Dgeneratoraccelerator2.gpu.profile=true");
        }
        GpuDevice d = device();
        GpuProbe.Setup s = GpuDatapack.setup(Long.getLong("ga2.gpu.seed", 1234567L));
        GpuWorldgen[] wgs = { open(d, s, "whole", false), open(d, s, "parts", true) };
        int side = Integer.getInteger("ga2.gpu.square", 12), rounds = Integer.getInteger("ga2.gpu.rounds", 6);
        List<java.util.Map<String, List<Double>>> per = List.of(new java.util.TreeMap<>(), new java.util.TreeMap<>());
        for (int round = 0; round < rounds; round++) {
            for (int t = 0; t < 2; t++) {
                int w = (round + t) & 1;
                java.util.Map<String, Long> before = deviceNanos(wgs[w]);
                surfaceRound(s, wgs[w], 2000 + round * 40, -3000 + round * 40, side);
                java.util.Map<String, Long> after = deviceNanos(wgs[w]);
                // the first round warms up
                if (round > 0) {
                    for (var e : after.entrySet()) {
                        double usPerChunk = (e.getValue() - before.getOrDefault(e.getKey(), 0L)) / 1e3 / (side * side);
                        per.get(w).computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(usPerChunk);
                    }
                }
            }
        }
        log("device time per chunk, medians of %d rounds of %d chunks (whole / parts):", rounds - 1, side * side);
        double[] total = new double[2];
        for (String k : per.get(0).keySet()) {
            double a = median(per.get(0).get(k)), b = median(per.get(1).getOrDefault(k, List.of(0.0)));
            total[0] += a;
            total[1] += b;
            log("  %-16s %8.2f / %8.2f us  (%+.1f%%)", k, a, b, a == 0 ? 0 : (b - a) / a * 100);
        }
        log("  %-16s %8.2f / %8.2f us  (%+.1f%%)", "total", total[0], total[1], (total[1] - total[0]) / total[0] * 100);
        for (GpuWorldgen wg : wgs) {
            java.util.concurrent.CompletableFuture<Void> closed = new java.util.concurrent.CompletableFuture<>();
            d.submit(() -> {
                GpuRuntime.close(wg);
                closed.complete(null);
            });
            closed.get(60, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private static double median(List<Double> xs) {
        List<Double> sorted = xs.stream().sorted().toList();
        return sorted.get(sorted.size() / 2);
    }

    private static GpuWorldgen open(GpuDevice d, GpuProbe.Setup s, String name, boolean parts) {
        GpuWorldgen wg = GpuRuntime.worldgen(d, name, s.world.settings, s.world.randomState);
        wg.buildParts = parts;
        wg.surfaceInputs(s.world.generator, s.world.seed);
        wg.fluidPicker = s.world.fluidPicker;
        wg.levelHeights = s.world.heights;
        GpuRuntime.prepare(wg, s.world.biomeSource);
        d.addPump(wg);
        GpuRuntime.ready(wg);
        wg.prepareSurface();
        log("%s", wg.status());
        if (wg.surfaces == null) {
            throw new IllegalStateException("no GPU surface: " + wg.status());
        }
        return wg;
    }

    private static java.util.Map<String, Long> deviceNanos(GpuWorldgen wg) {
        java.util.Map<String, Long> out = new java.util.TreeMap<>();
        wg.deviceNanos.forEach((k, v) -> out.put(k, v.sum()));
        String[] surface = { "s.unpack", "s.build", "s.pack", "s.heights" };
        for (int i = 0; i < surface.length; i++) {
            out.put(surface[i], dev.denismasterherobrine.generatoraccelerator2.gpu.surface.SurfaceBatches.DEVICE_NANOS[i].sum());
        }
        return out;
    }

    /** Noise, biomes and the surface of side² chunks at (ox, oz), as GpuSteps and GpuSurfaceJob ask for them. */
    private static void surfaceRound(GpuProbe.Setup s, GpuWorldgen wg, int ox, int oz, int side) throws Exception {
        int n = side + 2;
        List<java.util.concurrent.CompletableFuture<GpuWorldgen.BiomeResult>> bf = new ArrayList<>();
        List<java.util.concurrent.CompletableFuture<GpuWorldgen.NoiseResult>> nf = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                net.minecraft.world.level.ChunkPos pos = new net.minecraft.world.level.ChunkPos(ox - 1 + i, oz - 1 + j);
                bf.add(wg.biomes(pos));
                boolean inner = i > 0 && j > 0 && i <= side && j <= side;
                nf.add(inner ? wg.noise(pos, GpuWorldgen.Beard.NONE) : java.util.concurrent.CompletableFuture.completedFuture(null));
            }
        }
        net.minecraft.world.level.chunk.ProtoChunk[] chunks = new net.minecraft.world.level.chunk.ProtoChunk[n * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int cx = ox - 1 + i, cz = oz - 1 + j;
                var c = s.world.emptyChunk(cx, cz);
                var b = bf.get(i * n + j).get(120, java.util.concurrent.TimeUnit.SECONDS);
                var structures = Structures.none(cx, cz, 1, 1).beardifierFor(cx, cz);
                dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.Apply.biomes(wg, c, b, () -> ((dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.NoiseChunkSamplerInvoker) c
                    .getOrCreateNoiseChunk(x -> net.minecraft.world.level.levelgen.NoiseChunk.forChunk(x, s.world.randomState, structures, s.world.settings, s.world.fluidPicker,
                        net.minecraft.world.level.levelgen.blending.Blender.empty()))).ga2$cachedClimateSampler(s.world.randomState.router(), s.world.settings.spawnTarget()));
                c.setPersistedStatus(net.minecraft.world.level.chunk.status.ChunkStatus.BIOMES);
                chunks[i * n + j] = c;
            }
        }
        var spec = wg.surfaceSpec;
        GpuTerrain.Spec tspec = wg.terrainSpec(() -> s.world.fluidPicker);
        List<java.util.concurrent.CompletableFuture<?>> sf = new ArrayList<>();
        for (int i = 1; i <= side; i++) {
            for (int j = 1; j <= side; j++) {
                int cx = ox - 1 + i, cz = oz - 1 + j;
                GpuWorldgen.NoiseResult res = nf.get(i * n + j).get(120, java.util.concurrent.TimeUnit.SECONDS);
                if (res == null) {
                    continue;
                }
                short[] win = new short[spec.windowInts];
                int qy0 = wg.minY >> 2, qx0 = cx * 4 - 1, qz0 = cz * 4 - 1;
                for (int iz = 0; iz < 6; iz++) {
                    for (int ix = 0; ix < 6; ix++) {
                        int qx = qx0 + ix, qz = qz0 + iz;
                        var c = chunks[((qx >> 2) - ox + 1) * n + (qz >> 2) - oz + 1];
                        for (int iy = 0; iy < spec.quartsY; iy++) {
                            win[(iy * 6 + iz) * 6 + ix] = (short) (int) wg.biomeIndex.get(c.getNoiseBiome(qx, qy0 + iy, qz));
                        }
                    }
                }
                int[] tops = new int[256];
                for (int col = 0; col < 256; col++) {
                    int top = res.tops()[col * 2];
                    tops[(col & 15) * 16 + (col >> 4)] = top == Integer.MIN_VALUE ? wg.minY : top + 1;
                }
                GpuTerrain terrain = new GpuTerrain(tspec, res.terrain(), cx, cz);
                int bx = cx * 16, bz = cz * 16;
                int[] cpar = { cx, cz, terrain.preliminarySurfaceLevel(bx, bz), terrain.preliminarySurfaceLevel(bx + 16, bz), terrain.preliminarySurfaceLevel(bx, bz + 16),
                    terrain.preliminarySurfaceLevel(bx + 16, bz + 16), 0, 0 };
                sf.add(wg.surfaces.submit(new net.minecraft.world.level.ChunkPos(cx, cz), res.storage(), res.meta(), tops, win, cpar));
            }
        }
        java.util.concurrent.CompletableFuture.allOf(sf.toArray(java.util.concurrent.CompletableFuture[]::new)).get(300, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * Parts whose processes fail build here: a JVM that cannot start ({@code java.home} names no JDK), and one that cannot run the
     * worker ({@code -Dga2.gpu.oldJavaHome=<a Java 17 home>}, which cannot load GA2's Java 21 classes).
     */
    @Test
    void fallbacks() throws Exception {
        GpuDevice d = device();
        String src = "__kernel void k_a(__global int* o) { o[0] = 1; }\n__kernel void k_b(__global int* o) { o[0] = 2; }\n"
            + "#ifdef GA2_NONCE\n__kernel void k_nonce(__global int* o) { o[0] = GA2_NONCE; }\n#endif\n";
        List<String> sources = List.of(ProgramParts.only(src, List.of("k_a", "k_nonce")), ProgramParts.only(src, List.of("k_b", "k_nonce")));
        String home = System.getProperty("java.home");
        List<String> homes = new ArrayList<>(List.of(Path.of("build", "no-such-jdk").toAbsolutePath().toString()));
        if (System.getProperty("ga2.gpu.oldJavaHome") != null) {
            homes.add(System.getProperty("ga2.gpu.oldJavaHome"));
        }
        for (String h : homes) {
            System.setProperty("java.home", h);
            System.setProperty("generatoraccelerator2.gpu.noBinaryCache", "true");
            try {
                var b = dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.ProgramCache.start(d, sources, Gpu.BUILD_OPTIONS + " -D GA2_NONCE=" + NONCE.getAndIncrement());
                for (int i = 0; i < b.size(); i++) {
                    long p = b.take(i, new StringBuilder());
                    d.cl.releaseKernel(d.cl.createKernel(p, i == 0 ? "k_a" : "k_b"));
                    d.cl.releaseProgram(p);
                }
                log("java.home %s: parts made %s", h, String.join(", ", b.how));
                if (!java.util.Arrays.stream(b.how).allMatch("here"::equals)) {
                    throw new AssertionError("a failed process's part was not built here");
                }
            } finally {
                System.setProperty("java.home", home);
                System.clearProperty("generatoraccelerator2.gpu.noBinaryCache");
            }
        }
    }

    @Test
    void kernels() throws Exception {
        GpuDevice d = device();
        for (Path file : sources(d)) {
            String src = Files.readString(file);
            List<String> names = ProgramParts.names(src);
            log("%s: %,d chars, %d kernels", file.getFileName(), src.length(), names.size());
            log("  whole program: %.1f s", coldBuild(d, src));
            List<String[]> rows = new ArrayList<>();
            double sum = 0;
            for (String k : names) {
                if (k.equals("k_nonce")) {
                    continue;
                }
                String part = ProgramParts.only(src, List.of(k, "k_nonce"));
                double s = coldBuild(d, part);
                sum += s;
                rows.add(new String[] { k, String.format(Locale.ROOT, "%6.2f s  (%,d chars)", s, part.length()) });
            }
            for (String[] r : rows) {
                log("  %-22s %s", r[0], r[1]);
            }
            log("  sum of kernels alone: %.1f s", sum);
        }
    }
}
