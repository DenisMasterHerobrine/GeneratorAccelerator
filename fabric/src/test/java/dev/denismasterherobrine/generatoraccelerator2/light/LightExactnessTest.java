package dev.denismasterherobrine.generatoraccelerator2.light;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.denismasterherobrine.generatoraccelerator2.features.HarnessWorld;
import dev.denismasterherobrine.generatoraccelerator2.parallellight.mixin.DataLayerStorageMapAccessor;
import dev.denismasterherobrine.generatoraccelerator2.parallellight.mixin.LayerLightSectionStorageAccessor;
import dev.denismasterherobrine.generatoraccelerator2.parallellight.mixin.LightEngineAccessor;
import dev.denismasterherobrine.generatoraccelerator2.testutil.RegionChunks;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Light from GA2's light engine hooks equals vanilla's. The terrain is real: noise and surface of the overworld and the
 * nether, plus light sources, slabs, stairs, glass, leaves and water placed at random. It is lit through the same
 * sequence of engine calls the server makes: section status, light enabled, light sources per chunk in batches, then
 * block changes after lighting. The block changes include sections that become empty or non-empty. Every section's
 * block and sky light, the section types, a sample of combined brightness and the exact sequence of "light changed"
 * notifications must match.
 */
public class LightExactnessTest {
    private static final int SIDE = 5;

    @Test
    void overworld() {
        compare(TerrainHarness.Preset.OVERWORLD, 1234567L, 0, 0, true);
        compare(TerrainHarness.Preset.OVERWORLD, 1234567L, -300, 412, true);
        compare(TerrainHarness.Preset.AMPLIFIED, -42L, 40, -7, true);
    }

    /** Full chunks of a dev-run world (features, structures, real light sources), when one is around. */
    @Test
    void realChunks() throws IOException {
        Path region = RegionChunks.defaultRegion();
        Assumptions.assumeTrue(region != null, "no dev-run region file");
        TerrainHarness.World world = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L);
        ProtoChunk[] chunks = RegionChunks.load(region, 4, 6, 8, world);
        Assumptions.assumeTrue(chunks != null, "region square not fully generated");
        compare(world, chunks, true, 99L, "region " + region.getFileName());
    }

    @Test
    void nether() {
        compare(TerrainHarness.Preset.NETHER, 1234567L, 3, 9, false);
    }

    private static void compare(TerrainHarness.Preset preset, long seed, int x0, int z0, boolean sky) {
        compare(TerrainHarness.world(preset, seed), terrain(preset, seed, x0, z0), sky, seed, preset + " at " + x0 + "," + z0);
    }

    /**
     * Light published as snapshots of the changed sections ({@link LightSnapshot}) equals light published by vanilla's
     * clones. The published maps are checked after every batch, and the light read through them at the end.
     */
    @Test
    void snapshots() {
        TerrainHarness.World world = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L);
        ProtoChunk[] base = terrain(TerrainHarness.Preset.OVERWORLD, 1234567L, 16, -40);
        Run clones;
        boolean enabled = LightSnapshot.ENABLED;
        try {
            LightSnapshot.ENABLED = false;
            clones = run(world, base, true, 5L, true, 36L, 2, LightExactnessTest::checkPublished);
        } finally {
            LightSnapshot.ENABLED = enabled;
        }
        long deltas = LightSnapshot.DELTAS.sum();
        Run snapshots = run(world, base, true, 5L, true, 36L, 2, LightExactnessTest::checkPublished);
        assertTrue(!clones.snapshotsWereOn, "the clone run had snapshots off");
        assertTrue(snapshots.snapshotsWereOn, "snapshots were on");
        assertTrue(LightSnapshot.DELTAS.sum() - deltas > 20, "snapshots built from the changed sections: " + (LightSnapshot.DELTAS.sum() - deltas));
        same(clones, snapshots, base.length, "overworld 16,-40, snapshots against clones");
    }

    private static void compare(TerrainHarness.World world, ProtoChunk[] base, boolean sky, long seed, String where) {
        Run vanilla;
        boolean snapshots = LightSnapshot.ENABLED;
        try {
            FastLight.ENABLED = false;
            LightSnapshot.ENABLED = false;
            vanilla = run(world, base, sky, seed);
        } finally {
            FastLight.ENABLED = true;
            LightSnapshot.ENABLED = snapshots;
        }
        Run fast = run(world, base, sky, seed, true, seed * 7 + 1, 3, LightExactnessTest::checkPublished);
        assertTrue(fast.fastWasOn, "the fast engine was on");
        assertTrue(!vanilla.fastWasOn, "the vanilla run had it off");
        assertTrue(!vanilla.snapshotsWereOn, "the vanilla run had snapshots off");
        same(vanilla, fast, base.length, where);
    }

    private static void same(Run vanilla, Run fast, int chunks, String where) {
        assertEquals(vanilla.layers.keySet(), fast.layers.keySet(), where + ": sections with light data");
        int nonEmpty = 0;
        for (Map.Entry<String, byte[]> e : vanilla.layers.entrySet()) {
            assertArrayEquals(e.getValue(), fast.layers.get(e.getKey()), where + ": light of " + e.getKey());
            if (e.getValue() != null) {
                nonEmpty++;
            }
        }
        assertTrue(nonEmpty > chunks * 10, where + ": sections compared " + nonEmpty);
        assertEquals(vanilla.types, fast.types, where + ": section types");
        assertArrayEquals(vanilla.brightness, fast.brightness, where + ": brightness sample");
        assertEquals(vanilla.updates.size(), fast.updates.size(), where + ": light change notifications");
        assertEquals(vanilla.updates, fast.updates, where + ": light change notification order");
    }

    /** Noise + surface for a SIDE x SIDE square, then light sources and shaped/translucent blocks placed at random. */
    static ProtoChunk[] terrain(TerrainHarness.Preset preset, long seed, int x0, int z0) {
        ProtoChunk[] chunks = new ProtoChunk[SIDE * SIDE];
        Random random = new Random(seed ^ (x0 * 31L + z0));
        BlockState[] sources = { Blocks.GLOWSTONE.defaultBlockState(), Blocks.TORCH.defaultBlockState(), Blocks.LAVA.defaultBlockState(),
            Blocks.SEA_LANTERN.defaultBlockState(), Blocks.SHROOMLIGHT.defaultBlockState(), Blocks.REDSTONE_TORCH.defaultBlockState(),
            Blocks.SOUL_TORCH.defaultBlockState() };
        BlockState[] shapes = { Blocks.STONE_SLAB.defaultBlockState(), Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP),
            Blocks.OAK_STAIRS.defaultBlockState(), Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST),
            Blocks.GLASS.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState(), Blocks.WATER.defaultBlockState(), Blocks.ICE.defaultBlockState(),
            Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.COBWEB.defaultBlockState() };
        for (int dz = 0; dz < SIDE; dz++) {
            for (int dx = 0; dx < SIDE; dx++) {
                TerrainHarness.Site site = TerrainHarness.site(preset, seed, x0 + dx, z0 + dz);
                ProtoChunk chunk = site.center();
                TerrainHarness.vanillaSurface(site, chunk);
                int minY = chunk.getMinBuildHeight();
                int height = chunk.getHeight();
                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                for (int k = 0; k < 160; k++) {
                    pos.set(chunk.getPos().getMinBlockX() + random.nextInt(16), minY + random.nextInt(height), chunk.getPos().getMinBlockZ() + random.nextInt(16));
                    BlockState state = k % 3 == 0 ? sources[random.nextInt(sources.length)] : shapes[random.nextInt(shapes.length)];
                    if (k % 3 != 0 || chunk.getBlockState(pos).isAir()) {
                        chunk.setBlockState(pos, state, false);
                    }
                }
                // a floating canopy so sky light has shade to fill
                int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, 8, 8) + 6;
                if (top < minY + height - 1) {
                    for (int x = 2; x < 14; x++) {
                        for (int z = 3; z < 12; z++) {
                            pos.set(chunk.getPos().getMinBlockX() + x, top, chunk.getPos().getMinBlockZ() + z);
                            chunk.setBlockState(pos, (x + z) % 5 == 0 ? Blocks.GLASS.defaultBlockState() : Blocks.OAK_LEAVES.defaultBlockState(), false);
                        }
                    }
                }
                chunks[dx + dz * SIDE] = chunk;
            }
        }
        return chunks;
    }

    static final class Run {
        final Map<String, byte[]> layers = new LinkedHashMap<>();
        final List<String> types = new ArrayList<>();
        final List<String> updates = new ArrayList<>();
        int[] brightness;
        boolean fastWasOn;
        boolean snapshotsWereOn;
        long lightNanos;
        long initNanos;
        long sourcesNanos;
        long updatesNanos;
        long updateNanos;
    }

    static Run run(TerrainHarness.World world, ProtoChunk[] base, boolean sky, long seed) {
        return run(world, base, sky, seed, true);
    }

    /** @param changes whether to make block changes after lighting (the bench measures lighting alone) */
    static Run run(TerrainHarness.World world, ProtoChunk[] base, boolean sky, long seed, boolean changes) {
        return run(world, base, sky, seed, changes, seed * 7 + 1, 3);
    }

    /** @param orderSeed shuffles the order chunks are lit in; @param batch chunks lit per batch of updates */
    static Run run(TerrainHarness.World world, ProtoChunk[] base, boolean sky, long seed, boolean changes, long orderSeed, int batch) {
        return run(world, base, sky, seed, changes, orderSeed, batch, null);
    }

    /** @param afterBatch called after every batch of updates, or null */
    static Run run(TerrainHarness.World world, ProtoChunk[] base, boolean sky, long seed, boolean changes, long orderSeed, int batch,
                   @Nullable Consumer<LevelLightEngine> afterBatch) {
        Run out = new Run();
        int side = (int) Math.round(Math.sqrt(base.length));
        LevelHeightAccessor heights = world.heights;
        ProtoChunk[] chunks = HarnessWorld.clone(world.registries, base, heights);
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
        LightChunkGetter getter = new LightChunkGetter() {
            @Nullable
            @Override
            public LightChunk getChunkForLighting(int x, int z) {
                return byPos.get(ChunkPos.asLong(x, z));
            }

            @Override
            public BlockGetter getLevel() {
                return level;
            }

            @Override
            public void onLightUpdate(LightLayer layer, SectionPos pos) {
                out.updates.add(layer + " " + pos.x() + "," + pos.y() + "," + pos.z());
            }
        };
        long t0 = System.nanoTime();
        LevelLightEngine engine = new LevelLightEngine(getter, true, sky);
        out.fastWasOn = FastLight.on;
        out.snapshotsWereOn = LightSnapshot.on;

        // INITIALIZE_LIGHT: light sources of the column, section status (pre task), then light enabled = false, retain = false (post task)
        for (ProtoChunk c : chunks) {
            c.setStatus(ChunkStatus.INITIALIZE_LIGHT);
            c.setLightEngine(engine);
            c.initializeLightSources();
            LevelChunkSection[] sections = c.getSections();
            for (int i = 0; i < sections.length; i++) {
                if (!sections[i].hasOnlyAir()) {
                    engine.updateSectionStatus(SectionPos.of(c.getPos(), c.getSectionYFromSectionIndex(i)), false);
                }
            }
        }
        update(engine, afterBatch);
        for (ProtoChunk c : chunks) {
            engine.setLightEnabled(c.getPos(), false);
            engine.retainData(c.getPos(), false);
        }
        out.initNanos = System.nanoTime() - t0;

        // LIGHT: light sources per chunk, three chunks per batch, in a shuffled order
        List<ProtoChunk> order = new ArrayList<>(List.of(chunks));
        Collections.shuffle(order, new Random(orderSeed));
        for (int i = 0; i < order.size(); i++) {
            long ts = System.nanoTime();
            engine.propagateLightSources(order.get(i).getPos());
            long tu = System.nanoTime();
            out.sourcesNanos += tu - ts;
            if (i % batch == batch - 1 || i == order.size() - 1) {
                update(engine, afterBatch);
                out.updatesNanos += System.nanoTime() - tu;
            }
        }
        out.lightNanos = System.nanoTime() - t0;
        long t1 = System.nanoTime();
        for (ProtoChunk c : chunks) {
            c.setStatus(ChunkStatus.LIGHT);
        }

        // block changes after lighting: sources added and removed, holes, roofs, a whole section emptied and one filled
        Random random = new Random(seed * 13 + 5);
        BlockState[] palette = { Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(), Blocks.GLOWSTONE.defaultBlockState(),
            Blocks.TORCH.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState(), Blocks.WATER.defaultBlockState(), Blocks.STONE_SLAB.defaultBlockState(),
            Blocks.GLASS.defaultBlockState(), Blocks.LAVA.defaultBlockState() };
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minX = chunks[0].getPos().getMinBlockX();
        int minZ = chunks[0].getPos().getMinBlockZ();
        for (int k = 0; changes && k < 1500; k++) {
            pos.set(minX + random.nextInt(16 * side), heights.getMinBuildHeight() + random.nextInt(heights.getHeight()), minZ + random.nextInt(16 * side));
            byPos.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4)).setBlockState(pos, palette[random.nextInt(palette.length)], false);
            if (k % 40 == 39) {
                update(engine, afterBatch);
            }
        }
        ProtoChunk middle = chunks[side / 2 + side / 2 * side];
        int emptied = middle.getMinBuildHeight() + 16 * 3;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    middle.setBlockState(pos.set(middle.getPos().getMinBlockX() + x, emptied + y, middle.getPos().getMinBlockZ() + z), Blocks.AIR.defaultBlockState(), false);
                }
            }
        }
        update(engine, afterBatch);
        int filled = middle.getMaxBuildHeight() - 24;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                middle.setBlockState(pos.set(middle.getPos().getMinBlockX() + x, filled, middle.getPos().getMinBlockZ() + z), Blocks.STONE.defaultBlockState(), false);
            }
        }
        update(engine, afterBatch);
        while (engine.hasLightWork()) {
            update(engine, afterBatch);
        }

        out.updateNanos = System.nanoTime() - t1;
        int cx0 = chunks[0].getPos().x;
        int cz0 = chunks[0].getPos().z;
        for (int cz = cz0 - 1; cz <= cz0 + side; cz++) {
            for (int cx = cx0 - 1; cx <= cx0 + side; cx++) {
                for (int sy = engine.getMinLightSection(); sy < engine.getMaxLightSection(); sy++) {
                    SectionPos section = SectionPos.of(cx, sy, cz);
                    for (LightLayer layer : LightLayer.values()) {
                        DataLayer data = engine.getLayerListener(layer).getDataLayerData(section);
                        out.layers.put(layer + " " + cx + "," + sy + "," + cz, data == null ? null : data.copy().getData());
                        out.types.add(engine.getDebugSectionType(layer, section).display());
                    }
                }
            }
        }
        out.brightness = new int[4096];
        Random sample = new Random(3);
        for (int i = 0; i < out.brightness.length; i++) {
            pos.set(minX - 8 + sample.nextInt(16 * side + 16), heights.getMinBuildHeight() - 8 + sample.nextInt(heights.getHeight() + 16), minZ - 8 + sample.nextInt(16 * side + 16));
            out.brightness[i] = engine.getRawBrightness(pos, 0) * 100 + engine.getLayerListener(LightLayer.BLOCK).getLightValue(pos);
        }
        return out;
    }

    private static void update(LevelLightEngine engine, @Nullable Consumer<LevelLightEngine> afterBatch) {
        engine.runLightUpdates();
        if (afterBatch != null) {
            afterBatch.accept(engine);
        }
    }

    private static final Field VISIBLE;

    static {
        try {
            VISIBLE = LayerLightSectionStorage.class.getDeclaredField("visibleSectionData");
            VISIBLE.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * After a batch, the published section map of each light layer holds the working map's sections, the same objects. A
     * clone of the working map at the end of the batch, vanilla's publish, holds exactly that. With snapshots on, every
     * publish of a non-empty map is a snapshot. Checks lookups only, so the snapshots are not filled.
     */
    static void checkPublished(LevelLightEngine engine) {
        for (LightLayer layer : LightLayer.values()) {
            if (!(engine.getLayerListener(layer) instanceof LightEngineAccessor listener)) {
                continue;
            }
            LayerLightSectionStorage<?> storage = listener.ga2pl$storage();
            Long2ObjectOpenHashMap<DataLayer> working = ((DataLayerStorageMapAccessor) ((LayerLightSectionStorageAccessor) storage).ga2pl$updatingSectionData()).ga2pl$map();
            Long2ObjectOpenHashMap<DataLayer> published;
            try {
                published = ((DataLayerStorageMapAccessor) VISIBLE.get(storage)).ga2pl$map();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
            if (LightSnapshot.on && !working.isEmpty()) {
                assertTrue(published instanceof LightSnapshot, layer + ": published map is a snapshot");
            }
            assertEquals(working.size(), published.size(), layer + ": published sections");
            for (Long2ObjectMap.Entry<DataLayer> e : Long2ObjectMaps.fastIterable(working)) {
                if (published.get(e.getLongKey()) != e.getValue()) {
                    fail(layer + ": published section " + SectionPos.of(e.getLongKey()) + " is " + published.get(e.getLongKey()) + ", working " + e.getValue());
                }
            }
        }
    }
}
