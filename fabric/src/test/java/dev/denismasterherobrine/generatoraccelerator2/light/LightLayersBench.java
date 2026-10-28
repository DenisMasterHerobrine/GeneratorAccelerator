package dev.denismasterherobrine.generatoraccelerator2.light;

import dev.denismasterherobrine.generatoraccelerator2.features.HarnessWorld;
import dev.denismasterherobrine.generatoraccelerator2.testutil.RegionChunks;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.thread.ProcessorHandle;
import net.minecraft.util.thread.ProcessorMailbox;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.Path;

/**
 * The light thread's capacity with block light and sky light at once ({@link LightLayers}) and one after the other. A square of
 * 15x15 chunks (noise, surface, light sources and shapes at random) is initialized and lit through a real
 * {@code ThreadedLevelLightEngine} by four threads that queue the tasks as fast as they can, so the light thread is the limit.
 * Times the lighting from the first light task to the last chunk's result, and the light thread's CPU in it.
 * {@code gradlew :fabric:test --tests "*LightLayersBench*" -Dga2.bench=layers}
 */
@EnabledIfSystemProperty(named = "ga2.bench", matches = "layers")
public class LightLayersBench {
    @Test
    void capacity() throws Exception {
        TerrainHarness.World world = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L);
        ProtoChunk[] base;
        String file = System.getProperty("ga2.bench.regionFile");
        if (file != null) {
            // full chunks of a vanilla world, as generated
            base = RegionChunks.load(Path.of(file), 8, 8, 15, world);
            Assumptions.assumeTrue(base != null, "region square not fully generated");
        } else {
            // generated terrain with light sources and shapes at random: far more block light than a real world
            List<ProtoChunk> tiles = new ArrayList<>();
            for (int tz = 0; tz < 3; tz++) {
                for (int tx = 0; tx < 3; tx++) {
                    tiles.addAll(List.of(LightExactnessTest.terrain(TerrainHarness.Preset.OVERWORLD, 1234567L, 100 + tx * 5, -60 + tz * 5)));
                }
            }
            base = tiles.toArray(ProtoChunk[]::new);
        }
        boolean enabled = LightLayers.ENABLED;
        try {
            for (int round = 0; round < 8; round++) {
                for (boolean layers : new boolean[] { false, true }) {
                    LightLayers.ENABLED = layers;
                    long[] r = run(world, base, round);
                    if (round >= 2) {
                        System.out.println(String.format(Locale.ROOT, "light layers bench: %s, round %d: %d chunks lit in %.1f ms (%.3f ms per chunk), light thread CPU %.3f ms per chunk",
                            layers ? "layers at once" : "one after the other", round, base.length, r[0] / 1e6, r[0] / 1e6 / base.length, r[1] / 1e6 / base.length));
                    }
                }
            }
        } finally {
            LightLayers.ENABLED = enabled;
        }
    }

    /** Returns the wall nanoseconds of the lighting and the light thread's CPU nanoseconds in it. */
    private static long[] run(TerrainHarness.World world, ProtoChunk[] base, int round) throws Exception {
        ProtoChunk[] chunks = HarnessWorld.clone(world.registries, base, world.heights);
        AtomicReference<Thread> light = new AtomicReference<>();
        ExecutorService lightThread = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "bench light");
            light.set(t);
            return t;
        });
        ExecutorService workers = Executors.newFixedThreadPool(4);
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        try {
            ThreadedLevelLightEngine engine = new ThreadedLevelLightEngine(DirectLightTasksTest.getter(chunks, world.heights), DirectLightTasksTest.chunkMap(),
                true, ProcessorMailbox.create(lightThread, "light"), ProcessorHandle.of("sorter", m -> {}));
            List<ProtoChunk> order = new ArrayList<>(List.of(chunks));
            Collections.shuffle(order, new Random(round));
            List<CompletableFuture<?>> steps = new ArrayList<>();
            for (ProtoChunk c : order) {
                steps.add(CompletableFuture.supplyAsync(() -> {
                    c.setStatus(ChunkStatus.INITIALIZE_LIGHT);
                    c.initializeLightSources();
                    return engine.initializeLight(c, false);
                }, workers).thenCompose(f -> f));
            }
            CompletableFuture.allOf(steps.toArray(CompletableFuture[]::new)).get(120, TimeUnit.SECONDS);
            long cpu0 = threads.getThreadCpuTime(light.get().getId());
            long t0 = System.nanoTime();
            steps.clear();
            for (ProtoChunk c : order) {
                steps.add(CompletableFuture.supplyAsync(() -> engine.lightChunk(c, false), workers).thenCompose(f -> f));
            }
            CompletableFuture.allOf(steps.toArray(CompletableFuture[]::new)).get(120, TimeUnit.SECONDS);
            long wall = System.nanoTime() - t0;
            return new long[] { wall, threads.getThreadCpuTime(light.get().getId()) - cpu0 };
        } finally {
            workers.shutdownNow();
            lightThread.shutdownNow();
        }
    }
}
