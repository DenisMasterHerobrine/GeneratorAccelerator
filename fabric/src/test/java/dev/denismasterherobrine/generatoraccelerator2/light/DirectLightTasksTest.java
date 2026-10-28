package dev.denismasterherobrine.generatoraccelerator2.light;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.features.HarnessWorld;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.util.thread.ProcessorHandle;
import net.minecraft.util.thread.ProcessorMailbox;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * Light tasks handed straight to the light thread ({@link LightTasks}), with block light and sky light updated at once
 * ({@link LightLayers}), on a real {@code ThreadedLevelLightEngine}. Four threads initialize and light the chunks of a square at
 * once, as generation workers do, and nothing asks for updates from outside: every step's future must complete on its own. The
 * chunk priority sorter must never be used. Then 600 block changes in rounds of 40 (light sources placed and removed, holes,
 * roofs, water), a whole section emptied and a layer filled. The light read afterwards must equal the light of the same chunks
 * lit with the same engine calls on one thread: every chunk initialized in one batch, lit three chunks per batch, and each round
 * of changes in one batch.
 */
public class DirectLightTasksTest {
    private static final int ROUND = 40;

    @Test
    void tasksRunAndLightMatches() throws Exception {
        assertTrue(Ga2LightTasks.class.isAssignableFrom(ThreadedLevelLightEngine.class), "the light engine takes tasks directly");
        assertTrue(LightTasks.on(), "direct light tasks are on");
        assertTrue(LightLayers.on(), "light layers at once are on");
        TerrainHarness.World world = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L);
        ProtoChunk[] base = LightExactnessTest.terrain(TerrainHarness.Preset.OVERWORLD, 1234567L, 7, -20);
        Map<String, byte[]> reference = reference(world, base);
        long queued = LightTasks.snapshot()[0];
        long layered = LightLayers.snapshot()[0];
        for (int round = 0; round < 3; round++) {
            Map<String, byte[]> direct = direct(world, base, round);
            assertEquals(reference.keySet(), direct.keySet(), "round " + round + ": sections with light data");
            for (Map.Entry<String, byte[]> e : reference.entrySet()) {
                assertArrayEquals(e.getValue(), direct.get(e.getKey()), "round " + round + ": light of " + e.getKey());
            }
        }
        // at least two initialization and two light tasks per chunk and round, then the changes' tasks
        assertTrue(LightTasks.snapshot()[0] - queued > 3L * 4 * base.length, "tasks queued directly");
        assertTrue(LightLayers.snapshot()[0] - layered > 3L * 10, "updates with the layers at once: " + (LightLayers.snapshot()[0] - layered));
    }

    /** The block changes after lighting, by round: random ones, then a section of the middle chunk emptied, then a layer filled. */
    private static List<List<Map.Entry<BlockPos, BlockState>>> changes(ProtoChunk[] chunks, LevelHeightAccessor heights) {
        Random random = new Random(4242);
        BlockState[] palette = { Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(), Blocks.GLOWSTONE.defaultBlockState(),
            Blocks.TORCH.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState(), Blocks.WATER.defaultBlockState(), Blocks.STONE_SLAB.defaultBlockState(),
            Blocks.GLASS.defaultBlockState(), Blocks.LAVA.defaultBlockState() };
        int side = (int) Math.round(Math.sqrt(chunks.length));
        int minX = chunks[0].getPos().getMinBlockX();
        int minZ = chunks[0].getPos().getMinBlockZ();
        List<List<Map.Entry<BlockPos, BlockState>>> rounds = new ArrayList<>();
        for (int r = 0; r < 600 / ROUND; r++) {
            List<Map.Entry<BlockPos, BlockState>> round = new ArrayList<>();
            for (int k = 0; k < ROUND; k++) {
                BlockPos pos = new BlockPos(minX + random.nextInt(16 * side), heights.getMinBuildHeight() + random.nextInt(heights.getHeight()),
                    minZ + random.nextInt(16 * side));
                round.add(Map.entry(pos, palette[random.nextInt(palette.length)]));
            }
            rounds.add(round);
        }
        ProtoChunk middle = chunks[side / 2 + side / 2 * side];
        List<Map.Entry<BlockPos, BlockState>> emptied = new ArrayList<>();
        int y0 = middle.getMinBuildHeight() + 16 * 3;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    emptied.add(Map.entry(new BlockPos(middle.getPos().getMinBlockX() + x, y0 + y, middle.getPos().getMinBlockZ() + z), Blocks.AIR.defaultBlockState()));
                }
            }
        }
        rounds.add(emptied);
        List<Map.Entry<BlockPos, BlockState>> filled = new ArrayList<>();
        int top = middle.getMaxBuildHeight() - 24;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                filled.add(Map.entry(new BlockPos(middle.getPos().getMinBlockX() + x, top, middle.getPos().getMinBlockZ() + z), Blocks.STONE.defaultBlockState()));
            }
        }
        rounds.add(filled);
        return rounds;
    }

    private static void apply(ProtoChunk[] chunks, List<Map.Entry<BlockPos, BlockState>> round) {
        Map<Long, ProtoChunk> byPos = new HashMap<>();
        for (ProtoChunk c : chunks) {
            byPos.put(c.getPos().toLong(), c);
        }
        for (Map.Entry<BlockPos, BlockState> change : round) {
            BlockPos pos = change.getKey();
            byPos.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4)).setBlockState(pos, change.getValue(), false);
        }
    }

    /** The chunks lit on one thread through a plain light engine. */
    private static Map<String, byte[]> reference(TerrainHarness.World world, ProtoChunk[] base) {
        ProtoChunk[] chunks = HarnessWorld.clone(world.registries, base, world.heights);
        LevelLightEngine engine = new LevelLightEngine(getter(chunks, world.heights), true, true);
        for (ProtoChunk c : chunks) {
            c.setStatus(ChunkStatus.INITIALIZE_LIGHT);
            c.initializeLightSources();
            LevelChunkSection[] sections = c.getSections();
            for (int i = 0; i < sections.length; i++) {
                if (!sections[i].hasOnlyAir()) {
                    engine.updateSectionStatus(SectionPos.of(c.getPos(), c.getSectionYFromSectionIndex(i)), false);
                }
            }
        }
        engine.runLightUpdates();
        for (ProtoChunk c : chunks) {
            engine.setLightEnabled(c.getPos(), false);
            engine.retainData(c.getPos(), false);
        }
        List<ProtoChunk> order = new ArrayList<>(List.of(chunks));
        Collections.shuffle(order, new Random(78));
        for (int i = 0; i < order.size(); i++) {
            engine.propagateLightSources(order.get(i).getPos());
            if (i % 3 == 2 || i == order.size() - 1) {
                engine.runLightUpdates();
            }
        }
        for (ProtoChunk c : chunks) {
            c.setLightEngine(engine);
        }
        for (List<Map.Entry<BlockPos, BlockState>> round : changes(chunks, world.heights)) {
            apply(chunks, round);
            engine.runLightUpdates();
        }
        while (engine.hasLightWork()) {
            engine.runLightUpdates();
        }
        return read(engine, chunks);
    }

    /**
     * The chunks lit through a threaded light engine, four threads queueing the tasks in a shuffled order. Each round of changes
     * is made while the light thread is held, so the round's tasks make one update.
     */
    private static Map<String, byte[]> direct(TerrainHarness.World world, ProtoChunk[] base, int round) throws Exception {
        ProtoChunk[] chunks = HarnessWorld.clone(world.registries, base, world.heights);
        AtomicInteger sorterUses = new AtomicInteger();
        ProcessorHandle<ChunkTaskPriorityQueueSorter.Message<Runnable>> sorter = ProcessorHandle.of("sorter", m -> sorterUses.incrementAndGet());
        ExecutorService lightThread = Executors.newSingleThreadExecutor();
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            ThreadedLevelLightEngine engine = new ThreadedLevelLightEngine(getter(chunks, world.heights), chunkMap(), true,
                ProcessorMailbox.create(lightThread, "light"), sorter);
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
            CompletableFuture.allOf(steps.toArray(CompletableFuture[]::new)).get(60, TimeUnit.SECONDS);
            Collections.shuffle(order, new Random(round + 100));
            steps.clear();
            for (ProtoChunk c : order) {
                steps.add(CompletableFuture.supplyAsync(() -> engine.lightChunk(c, false), workers).thenCompose(f -> f));
            }
            CompletableFuture.allOf(steps.toArray(CompletableFuture[]::new)).get(60, TimeUnit.SECONDS);
            for (ProtoChunk c : chunks) {
                c.setLightEngine(engine);
            }
            for (List<Map.Entry<BlockPos, BlockState>> changes : changes(chunks, world.heights)) {
                CountDownLatch held = new CountDownLatch(1);
                CountDownLatch release = new CountDownLatch(1);
                lightThread.execute(() -> {
                    held.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                held.await(60, TimeUnit.SECONDS);
                apply(chunks, changes);
                CompletableFuture<?> done = pendingTasks(engine);
                release.countDown();
                done.get(60, TimeUnit.SECONDS);
            }
            assertEquals(0, sorterUses.get(), "tasks went through the sorter");
            return read(engine, chunks);
        } finally {
            workers.shutdownNow();
            lightThread.shutdownNow();
        }
    }

    static LightChunkGetter getter(ProtoChunk[] chunks, LevelHeightAccessor heights) {
        Map<Long, ProtoChunk> byPos = new HashMap<>();
        for (ProtoChunk c : chunks) {
            byPos.put(c.getPos().toLong(), c);
        }
        BlockGetter level = new BlockGetter() {
            @Nullable
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                ProtoChunk c = byPos.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
                return c == null ? Blocks.AIR.defaultBlockState() : c.getBlockState(pos);
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                return getBlockState(pos).getFluidState();
            }

            @Override
            public int getHeight() {
                return heights.getHeight();
            }

            @Override
            public int getMinBuildHeight() {
                return heights.getMinBuildHeight();
            }
        };
        return new LightChunkGetter() {
            @Nullable
            @Override
            public LightChunk getChunkForLighting(int x, int z) {
                return byPos.get(ChunkPos.asLong(x, z));
            }

            @Override
            public BlockGetter getLevel() {
                return level;
            }
        };
    }

    /** Both layers' published light of the square and the ring around it. */
    private static Map<String, byte[]> read(LevelLightEngine engine, ProtoChunk[] chunks) {
        Map<String, byte[]> layers = new LinkedHashMap<>();
        int side = (int) Math.round(Math.sqrt(chunks.length));
        int cx0 = chunks[0].getPos().x;
        int cz0 = chunks[0].getPos().z;
        for (int cz = cz0 - 1; cz <= cz0 + side; cz++) {
            for (int cx = cx0 - 1; cx <= cx0 + side; cx++) {
                for (int sy = engine.getMinLightSection(); sy < engine.getMaxLightSection(); sy++) {
                    SectionPos section = SectionPos.of(cx, sy, cz);
                    for (LightLayer layer : LightLayer.values()) {
                        DataLayer data = engine.getLayerListener(layer).getDataLayerData(section);
                        layers.put(layer + " " + cx + "," + sy + "," + cz, data == null ? null : data.copy().getData());
                    }
                }
            }
        }
        return layers;
    }

    /**
     * A chunk map without its constructor run. The light engine asks it for a chunk's queue level, a lambda the sorter would
     * read, and has it release a lit chunk's light ticket on the main thread. Those releases are queued and never run.
     */
    static ChunkMap chunkMap() throws Exception {
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        ChunkMap map = (ChunkMap) ((Unsafe) f.get(null)).allocateInstance(ChunkMap.class);
        Field main = ChunkMap.class.getDeclaredField("mainThreadExecutor");
        main.setAccessible(true);
        main.set(map, new BlockableEventLoop<Runnable>("ga2-test-main") {
            @Override
            protected Runnable wrapRunnable(Runnable task) {
                return task;
            }

            @Override
            protected boolean shouldRun(Runnable task) {
                return false;
            }

            @Override
            protected Thread getRunningThread() {
                return Thread.currentThread();
            }
        });
        return map;
    }

    /** Done once the tasks queued before it have run, as a no-op task queued after the light update (the engine's own addTask). */
    private static CompletableFuture<?> pendingTasks(ThreadedLevelLightEngine engine) throws ReflectiveOperationException {
        Class<?> type = Class.forName(ThreadedLevelLightEngine.class.getName() + "$TaskType");
        Object postUpdate = type.getEnumConstants()[1];
        Method add = ThreadedLevelLightEngine.class.getDeclaredMethod("addTask", int.class, int.class, type, Runnable.class);
        add.setAccessible(true);
        return CompletableFuture.runAsync(() -> {}, task -> {
            try {
                add.invoke(engine, 0, 0, postUpdate, task);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
