package dev.denismasterherobrine.generatoraccelerator2.gpu;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.GeneratorAccelerator;
import dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched.LithostitchedGpuBiomes;
import dev.denismasterherobrine.generatoraccelerator2.gpu.cl.ClDeviceInfo;
import dev.denismasterherobrine.generatoraccelerator2.gpu.cl.ClLoader;
import dev.denismasterherobrine.generatoraccelerator2.gpu.codegen.ClEmitter;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.BiomeHand;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.GpuDevice;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.GpuRuntime;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.GpuWorldgen;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.SelfTest;
import dev.denismasterherobrine.generatoraccelerator2.gpu.surface.SurfaceBatches;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.GeneratorPickerAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.scheduling.ChunkAccessAccessor;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import dev.denismasterherobrine.generatoraccelerator2.platform.Platform;
import dev.denismasterherobrine.generatoraccelerator2.scheduling.JvmStalls;
import dev.denismasterherobrine.generatoraccelerator2.scheduling.ParallelSteps;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import org.slf4j.Logger;

/**
 * Entry point for GPU world generation from the rest of GA2. On first use, the device is found and checked in the background.
 * That covers the OpenCL binding, device choice and an arithmetic self-test. Each dimension's program is built and checked
 * against the CPU path in the background on the dimension's first chunk. Until then, and whenever anything fails, chunks are
 * generated on the CPU.
 */
public final class Gpu {
    private Gpu() {}

    private static final Logger LOGGER = Ga2Log.get("GPU");
    public static final String BUILD_OPTIONS = "-cl-std=CL1.2 -cl-fp32-correctly-rounded-divide-sqrt";

    private static volatile boolean started;
    /** Bumped by every start and release. An init that finishes after a release closes its device again. */
    private static int generation;
    private static ClLoader.Loaded loaded;
    private static volatile GpuDevice device;
    private static volatile String status = "not started (no chunk generated yet)";
    private static volatile List<ClDeviceInfo> seen = List.of();
    private static final Map<RandomState, GpuWorldgen> WORLDS = new WeakHashMap<>();
    private static final ExecutorService PREPARE = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GA2 GPU prepare");
        t.setDaemon(true);
        // The driver compiles on this thread while every worker generates chunks on the CPU. At the lowest priority, a cold build
        // took two to three times as long under load (36-39 s instead of about 15 s for vanilla).
        t.setPriority(Thread.NORM_PRIORITY + 3);
        return t;
    });

    /** Where GA2 keeps the GPU path's files ({@code <game dir>/.ga2}). */
    public static Path dataDir() {
        try {
            return Platform.gameDir().resolve(".ga2");
        } catch (Throwable e) {
            return Path.of(System.getProperty("java.io.tmpdir"), "ga2-" + System.getProperty("user.name", "user"));
        }
    }

    /** The device once it is ready, else null. The first call starts device setup in the background. */
    public static GpuDevice device() {
        if (!started) {
            start();
        }
        return device;
    }

    private static synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        status = "starting";
        int gen = ++generation;
        Thread t = new Thread(() -> init(gen), "GA2 GPU init");
        t.setDaemon(true);
        t.start();
    }

    /** The binding, loaded once. A second load would define LWJGL's classes again in another class loader. */
    private static synchronized ClLoader.Loaded binding() throws Exception {
        if (loaded == null) {
            loaded = ClLoader.load(dataDir().resolve("gpu-lib"));
        }
        return loaded;
    }

    private static void init(int gen) {
        GpuDevice d = null;
        try {
            ClLoader.Loaded loaded = binding();
            List<ClDeviceInfo> all = loaded.api().devices(false);
            seen = all;
            if (all.isEmpty()) {
                status = "off: no OpenCL GPU found (" + loaded.description() + ")";
                LOGGER.info("off: no OpenCL GPU");
                return;
            }
            ClDeviceInfo pick = choose(all);
            int want = GpuConfig.device();
            if (pick == null && want >= all.size()) {
                status = "off: the config asks for GPU " + want + ", but " + all.size() + " GPU" + (all.size() == 1 ? " was" : "s were")
                    + " found (GPU 0 is the first; -1 picks one)";
                LOGGER.info("{}", status);
                return;
            }
            if (pick == null) {
                status = "off: no GPU with fp64 (" + all.size() + " GPUs found; the exact fp32 path is not ready yet)";
                LOGGER.info("{}", status);
                return;
            }
            d = GpuRuntime.open(loaded, pick);
            String bad = SelfTest.arithmetic(d, BUILD_OPTIONS);
            if (!bad.isEmpty()) {
                d.close();
                status = "off: " + pick.name().trim() + " fails the arithmetic self-test: " + bad;
                LOGGER.warn("{}", status);
                return;
            }
            synchronized (Gpu.class) {
                if (gen != generation) {
                    // Released while this device opened.
                    d.close();
                    return;
                }
                device = d;
                status = "ready: " + d.describe();
            }
            LOGGER.info("ready: {}", d.describe());
        } catch (Throwable e) {
            if (d != null) {
                d.close();
            }
            status = "off: " + e;
            LOGGER.warn("off: OpenCL setup failed", e);
        }
    }

    /** The configured device, else a discrete GPU with fp64 before an integrated one, then the larger memory. */
    private static ClDeviceInfo choose(List<ClDeviceInfo> all) {
        int want = GpuConfig.device();
        if (want >= 0) {
            return want < all.size() && all.get(want).hasFp64() ? all.get(want) : null;
        }
        return all.stream().filter(ClDeviceInfo::hasFp64)
            .max(Comparator.comparing((ClDeviceInfo d) -> !d.hostUnifiedMemory()).thenComparingLong(ClDeviceInfo::globalMemory)).orElse(null);
    }

    /**
     * Starts the device and the level's program as soon as the level loads. The level's first chunks (the spawn area) then need not
     * wait for the build.
     */
    public static void prewarm(ServerLevel level) {
        // Overworld only. Other dimensions build on their first chunk, since a pack can have many dimensions that nobody visits.
        if (!GpuConfig.enabled() || level.dimension() != Level.OVERWORLD || level.getChunkSource().getGenerator().getClass() != NoiseBasedChunkGenerator.class) {
            return;
        }
        NoiseBasedChunkGenerator generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        device();
        Thread t = new Thread(() -> {
            long until = System.nanoTime() + 120_000_000_000L;
            while (device == null && !status.startsWith("off") && System.nanoTime() < until) {
                LockSupport.parkNanos(20_000_000L);
            }
            // If the server stopped meanwhile, its level is not registered again. closeWorlds already ran.
            if (device != null && level.getServer().isRunning()) {
                worldgen(generator, generator.generatorSettings(), ((GeneratorPickerAccessor) (Object) generator)
                    .ga2$globalFluidPicker(), level.getChunkSource().randomState(), level);
            }
        }, "GA2 GPU prewarm");
        t.setDaemon(true);
        t.start();
    }

    /**
     * The GPU path for this generator and random state, or null while the device is not ready. It is created on first use, and its
     * program then builds in the background.
     */
    public static GpuWorldgen worldgen(NoiseBasedChunkGenerator generator, Holder<NoiseGeneratorSettings> settings, Supplier<Aquifer.FluidPicker> picker,
                                       RandomState randomState, ChunkAccess chunk) {
        return worldgen(generator, settings, picker, randomState, level(chunk));
    }

    private static GpuWorldgen worldgen(NoiseBasedChunkGenerator generator, Holder<NoiseGeneratorSettings> settings, Supplier<Aquifer.FluidPicker> picker,
                                        RandomState randomState, ServerLevel level) {
        GpuDevice d = device();
        if (d == null) {
            return null;
        }
        GpuWorldgen wg;
        synchronized (WORLDS) {
            wg = WORLDS.get(randomState);
            if (wg != null) {
                return wg;
            }
            // A step can still finish after its server stopped. closeWorlds runs after the server stops running, so an entry made
            // now would keep the stopped world until the next server stops. Such a step runs on the CPU.
            if (level != null && !level.getServer().isRunning()) {
                return null;
            }
            // The device was released meanwhile. A world made on it now would never be closed.
            if (d != device) {
                return null;
            }
            String name = level != null ? level.dimension().location().toString() : settings.unwrapKey().map(k -> k.location().toString()).orElse("?");
            wg = GpuRuntime.worldgen(d, name, settings.value(), randomState);
            WORLDS.put(randomState, wg);
            GpuWorldgen w = wg;
            PREPARE.execute(() -> prepare(w, d, generator, settings, picker, randomState, level));
        }
        return wg;
    }

    /** The server level a chunk was made for, or null (a chunk made outside a level, as in tests). */
    public static ServerLevel level(ChunkAccess chunk) {
        return ((ChunkAccessAccessor) chunk).ga2$levelHeightAccessor() instanceof ServerLevel l ? l : null;
    }

    private static void prepare(GpuWorldgen wg, GpuDevice d, NoiseBasedChunkGenerator generator, Holder<NoiseGeneratorSettings> settings,
                                Supplier<Aquifer.FluidPicker> picker, RandomState randomState, ServerLevel level) {
        if (wg.closed()) {
            // The world closed before its build started. Nothing was made yet.
            return;
        }
        try {
            if (level != null) {
                wg.surfaceInputs(generator, level.getSeed());
            }
            wg.fluidPicker = picker.get();
            wg.levelHeights = level;
            GpuRuntime.prepare(wg, generator.getBiomeSource());
            if (wg.closed()) {
                // The world closed during the build. Release what the build created. close releases only objects that exist.
                d.submit(() -> GpuRuntime.close(wg));
                return;
            }
            d.addPump(wg);
            String test;
            if (!GpuConfig.selfTest()) {
                test = "self-test off in the config";
            } else if (level == null) {
                test = "self-test skipped (no server level)";
            } else {
                long t0 = System.nanoTime();
                String diff = SelfTest.chunks(wg, generator, settings, picker, randomState, level, level.registryAccess().registryOrThrow(Registries.BIOME));
                if (diff != null) {
                    GpuRuntime.fail(wg, "self-test: " + diff);
                    return;
                }
                test = String.format(Locale.ROOT, "self-test passed (%d chunks identical to the CPU path, %.1f s)", SelfTest.CHUNK_COUNT,
                    (System.nanoTime() - t0) / 1e9);
            }
            GpuRuntime.ready(wg);
            if (!wg.ready()) {
                if (wg.closed()) {
                    d.submit(() -> GpuRuntime.close(wg));
                }
                return;
            }
            LOGGER.info("on: {}; {}", wg.status(), test);
            wg.prepareSurface();
        } catch (ClEmitter.Unsupported e) {
            GpuRuntime.fail(wg, e.getMessage());
            d.submit(() -> GpuRuntime.release(wg));
        } catch (Throwable e) {
            LOGGER.error("program for {} failed", wg.name, e);
            GpuRuntime.fail(wg, e.toString());
            // A build that failed partway made programs and buffers already.
            d.submit(() -> GpuRuntime.release(wg));
        }
    }

    /**
     * Releases every dimension's device objects and the device. The context alone holds VRAM even with no world on it. The next
     * chunk that asks for the GPU opens the device again.
     */
    public static void release(String why) {
        GpuDevice d;
        synchronized (Gpu.class) {
            if (!started) {
                return;
            }
            generation++;
            started = false;
            d = device;
            device = null;
            status = "released (" + why + ")";
        }
        closeWorlds(d);
        if (d != null) {
            // After the builds queued before. Each one releases what it made on this device.
            PREPARE.execute(d::close);
            LOGGER.info("released: {}", why);
        }
    }

    /** Config reload: turning the GPU off releases its memory at once. */
    public static void configReloaded() {
        if (!GpuConfig.enabled()) {
            release("disabled in the config");
        }
    }

    private static void closeWorlds(GpuDevice d) {
        List<GpuWorldgen> ws;
        synchronized (WORLDS) {
            ws = new ArrayList<>(WORLDS.values());
            WORLDS.clear();
        }
        for (GpuWorldgen w : ws) {
            // Marked immediately, so a program still building sees the flag ({@link #prepare}).
            GpuRuntime.markClosed(w);
            if (d != null) {
                d.submit(() -> GpuRuntime.close(w));
            }
        }
    }

    /** One line per loaded level: the chunk generator, the biome source, and whether the GPU path has a program there. */
    private static void levelLines(List<String> out) {
        MinecraftServer server = GeneratorAccelerator.server();
        if (server == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            ChunkGenerator generator = level.getChunkSource().getGenerator();
            GpuWorldgen w;
            synchronized (WORLDS) {
                w = WORLDS.get(level.getChunkSource().randomState());
            }
            String gpu = w != null ? "its GPU program: " + w.status().substring(w.name.length() + 2)
                : generator instanceof NoiseBasedChunkGenerator ? "no GPU program yet (made at the first chunk generated there)"
                : "not a noise generator: the GPU path runs only where it hands chunks to one";
            out.add("  level " + level.dimension().location() + ": generator " + Names.mojangName(generator.getClass()) + ", biome source "
                + Names.mojangName(generator.getBiomeSource().getClass()) + "; " + gpu);
        }
    }

    public static List<String> statusLines() {
        List<String> out = new ArrayList<>();
        out.add("GPU generation " + (GpuConfig.enabled() ? "enabled" : "disabled in the config") + "; device " + status);
        for (int i = 0; i < seen.size(); i++) {
            out.add("  GPU " + i + ": " + seen.get(i).describe());
        }
        synchronized (WORLDS) {
            for (GpuWorldgen w : WORLDS.values()) {
                out.add("  " + w.status());
            }
        }
        levelLines(out);
        out.add(String.format(Locale.ROOT, "  chunks: noise %,d on GPU, %,d on CPU; biomes %,d on GPU, %,d on CPU; %,d GPU errors; %,d batches, %,d regions (%,d let go once every chunk was answered); idle dimensions' memory given back %,d times, unused pooled region buffers %,d",
            GpuSteps.NOISE_GPU.sum(), GpuSteps.NOISE_CPU.sum(), GpuSteps.BIOMES_GPU.sum(), GpuSteps.BIOMES_CPU.sum(), GpuSteps.GPU_ERRORS.sum(),
            GpuWorldgen.BATCHES.sum(), GpuWorldgen.REGIONS.sum(), GpuWorldgen.REGIONS_DONE.sum(), GpuWorldgen.TRIMS.sum(), GpuWorldgen.POOL_RELEASED.sum()));
        if (GpuSteps.BIOMES_HANDED.sum() > 0) {
            out.add(String.format(Locale.ROOT, "  biomes on the CPU with the GPU's answers: %,d chunks; %,d climate points, %,d vanilla searches, %,d Biolith searches from the GPU",
                GpuSteps.BIOMES_HANDED.sum(), BiomeHand.POINTS.sum(),
                BiomeHand.NEAREST.sum(), BiomeHand.FITTEST.sum()));
        }
        out.add(String.format(Locale.ROOT, "  region buffers: %,d MB on the GPU now; %,d regions let go after going unused",
            GpuWorldgen.REGION_BYTES.sum() >> 20, GpuWorldgen.REGIONS_IDLE.sum()));
        long handed = LithostitchedGpuBiomes.HANDED_POINTS.sum();
        if (handed > 0) {
            out.add(String.format(Locale.ROOT, "  Lithostitched's biome injectors: %,d climate points from the GPU (not sampled on the CPU)", handed));
        }
        if (GpuWorldgen.C2D_CPU.sum() > 0) {
            out.add(String.format(Locale.ROOT, "  given back to the CPU: %,d chunks whose aquifer cells' order decides a cache_2d value",
                GpuWorldgen.C2D_CPU.sum()));
        }
        if (GpuSteps.ENHANCED_CHUNKS.sum() > 0) {
            out.add(String.format(Locale.ROOT, "  other mods' structure pieces (YUNG's API, Integrated API) filled on the GPU: %,d chunks", GpuSteps.ENHANCED_CHUNKS.sum()));
        }
        out.add(String.format(Locale.ROOT, "  without a NoiseChunk: %,d chunks (%s; levels asked beyond a chunk %,d); blocks asked for at the biome step %,d",
            GpuTerrain.CHUNKS.sum(), GpuTerrain.enabled() ? "on" : "off", GpuTerrain.LEVEL_FALLBACKS.sum(), GpuSteps.NOISE_EARLY.sum()));
        out.add(String.format(Locale.ROOT, "  surface on the GPU: %,d asked, %,d written, %,d left to the CPU, %,d late; %,d batches %s",
            GpuSurfaceJob.ASKED.sum(), GpuSurfaceJob.APPLIED.sum(), GpuSurfaceJob.CPU.sum(), GpuSurfaceJob.LATE.sum(),
            SurfaceBatches.BATCHES.sum(), SurfaceBatches.BATCH_LAT.describe()));
        out.add(String.format(Locale.ROOT, "  answers later than %d ms (the CPU did those chunks): %,d; dimensions paused for a while (GPU busy elsewhere): %,d",
            GpuWorldgen.ANSWER_MS, GpuWorldgen.LATE_ANSWERS.sum(), GpuWorldgen.PAUSES.sum()));
        out.add(String.format(Locale.ROOT, "  waits for answers made longer while the whole JVM stood still: %,d (it stood still %.1f s in all)",
            JvmStalls.STRETCHED.sum(),
            JvmStalls.total() / 1e9));
        out.add(GpuWorldgen.SHARE
            ? String.format(Locale.ROOT, "  done by the CPU while the GPU was behind: biomes %,d, blocks %,d, surfaces %,d",
                GpuWorldgen.SHARED_BIOMES.sum(), GpuWorldgen.SHARED_BLOCKS.sum(), GpuWorldgen.SHARED_SURFACES.sum())
            : "  sharing with the CPU: off (-Dgeneratoraccelerator2.gpu.share=false)");
        // Surface kernel device time, collected with -Dgeneratoraccelerator2.gpu.profile=true.
        LongAdder[] surfaceNanos = SurfaceBatches.DEVICE_NANOS;
        if (surfaceNanos[1].sum() > 0) {
            StringBuilder sd = new StringBuilder("  surface device time (profiled), ms in all:");
            for (int i = 0; i < surfaceNanos.length; i++) {
                sd.append(String.format(Locale.ROOT, " %s %.1f", SurfaceBatches.KERNELS[i], surfaceNanos[i].sum() / 1e6));
            }
            out.add(sd.toString());
        }
        out.add("  decorations wait for their locks " + ParallelSteps.FEATURES_LOCK_WAIT.describe());
        long batches = GpuWorldgen.BATCHES.sum();
        if (batches > 0) {
            out.add(String.format(Locale.ROOT, "  latency: biomes %s (written in %s); noise %s (queued %s, batch %s of %.1f chunks; written in %s, NoiseChunk %s); regions %s",
                GpuSteps.BIOMES_WAIT.describe(), GpuSteps.BIOMES_APPLY.describe(), GpuSteps.NOISE_WAIT.describe(), GpuWorldgen.QUEUE_LAT.describe(),
                GpuWorldgen.BATCH_LAT.describe(), (double) GpuWorldgen.BATCH_CHUNKS.sum() / batches, GpuSteps.NOISE_APPLY.describe(), GpuSteps.NOISE_CHUNK.describe(),
                GpuWorldgen.REGION_LAT.describe()));
            // Device time per kernel, collected with -Dgeneratoraccelerator2.gpu.profile=true. Compare with the latencies above.
            List<GpuWorldgen> profiled;
            synchronized (WORLDS) {
                profiled = new ArrayList<>(WORLDS.values());
            }
            for (GpuWorldgen w : profiled) {
                if (!w.deviceNanos.isEmpty()) {
                    StringBuilder dev = new StringBuilder("  device time (profiled), ms in all:");
                    w.deviceNanos.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()))
                        .forEach(e -> dev.append(String.format(Locale.ROOT, " %s %.0f", e.getKey(), e.getValue().sum() / 1e6)));
                    out.add(dev.toString());
                }
            }
        }
        long certCells = GpuWorldgen.CERT_CELLS.sum();
        if (certCells > 0) {
            out.add(String.format(Locale.ROOT,
                "  certified fp32: %,d cells decided, %,d rechecked in fp64 (%.4f%%, %,d corners), %,d batches over the recheck list (to the CPU)", certCells,
                GpuWorldgen.RECHECKED_CELLS.sum(), 100.0 * GpuWorldgen.RECHECKED_CELLS.sum() / certCells, GpuWorldgen.RECHECKED_CORNERS.sum(),
                GpuWorldgen.RECHECK_OVERFLOWS.sum()));
            StringBuilder why = new StringBuilder("  rechecked cells by reason:");
            for (int i = 0; i < GpuWorldgen.RECHECK_REASONS.length; i++) {
                long k = GpuWorldgen.RECHECK_REASONS[i].sum();
                if (k > 0) {
                    why.append(String.format(Locale.ROOT, " %s %,d;", ClEmitter.RECHECK_REASONS[i], k));
                }
            }
            out.add(why.toString());
        }
        if (GpuVerify.active()) {
            out.add(String.format(Locale.ROOT, "  verify: %,d chunks compared with the CPU path, %,d differ; terrain (surface levels, aquifer cells) %,d compared, %,d differ; surfaces against vanilla's %,d compared, %,d differ",
                GpuVerify.VERIFIED.get(), GpuVerify.DIFFERING.get(), GpuVerify.TERRAINS.get(), GpuVerify.TERRAINS_DIFFERING.get(), GpuVerify.SURFACES.get(),
                GpuVerify.SURFACES_DIFFERING.get()));
        }
        return out;
    }
}
