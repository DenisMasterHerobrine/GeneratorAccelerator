package dev.denismasterherobrine.generatoraccelerator2.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.aquifer.Ga2Aquifer;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.BoundSampler;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.FastFill;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.VeinRule;
import dev.denismasterherobrine.generatoraccelerator2.light.LightQueue;
import dev.denismasterherobrine.generatoraccelerator2.state.PoiStates;
import dev.denismasterherobrine.generatoraccelerator2.testutil.FillHarness;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.BoundedFloatFunction;
import net.minecraft.util.CubicSpline;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.Test;

/**
 * The fast paths whose results equal vanilla's either way are in place. An exactness test cannot tell them from vanilla's
 * code. They are the ore vein rule's transcription, the light engine's queues that keep their arrays, and the spline points
 * mapped without a stream.
 */
public class FastPathsActiveTest {
    @Test
    void veinRuleIsTranscribed() {
        TerrainHarness.World w = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L);
        NoiseChunk chunk = FillHarness.chunk(w.randomState, w.settings, 3, -7, DensityFunctions.BeardifierMarker.INSTANCE, w.fluidPicker);
        FastFill.Rules rules = (FastFill.Rules) (Object) chunk;
        Object[] made = rules.ga2$materialRules();
        assertNotNull(made, "the constructor's material rules were recorded");
        assertNotNull(made[1], "the overworld has ore veins");
        assertTrue(rules.ga2$fastVeinRule((NoiseChunk.BlockStateFiller) made[1]) instanceof VeinRule, "the vein rule's transcription is bound");
    }

    @Test
    void samplersAndAquiferMakeNoObjectsPerPoint() throws Exception {
        TerrainHarness.World w = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L);
        Field pure = BoundSampler.class.getDeclaredField("pure");
        pure.setAccessible(true);
        BoundSampler shared = BoundSampler.lookup(w.randomState.sampler());
        assertNotNull(shared, "the RandomState's sampler is bound");
        assertTrue(pure.getBoolean(shared), "the overworld's climate program calls no other function: its points are a moved context");
        NoiseChunk chunk = FillHarness.chunk(w.randomState, w.settings, 3, -7, DensityFunctions.BeardifierMarker.INSTANCE, w.fluidPicker);
        Aquifer aquifer = chunk.aquifer();
        Field xoroshiro = aquifer.getClass().getDeclaredField("ga2$xoroshiro");
        xoroshiro.setAccessible(true);
        assertTrue(xoroshiro.getBoolean(aquifer), "the aquifer draws its cell centres from the Xoroshiro seeds");
        assertTrue(aquifer instanceof Ga2Aquifer, "carvers ask the aquifer by coordinates");
    }

    @Test
    void lightQueuesKeepTheirArrays() throws Exception {
        TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L);
        BlockGetter level = new BlockGetter() {
            @Nullable
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                return Blocks.AIR.defaultBlockState();
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                return Fluids.EMPTY.defaultFluidState();
            }

            @Override
            public int getHeight() {
                return 384;
            }

            @Override
            public int getMinY() {
                return -64;
            }
        };
        LightChunkGetter getter = new LightChunkGetter() {
            @Nullable
            @Override
            public LightChunk getChunkForLighting(int x, int z) {
                return null;
            }

            @Override
            public BlockGetter getLevel() {
                return level;
            }

            @Override
            public void onLightUpdate(LightLayer layer, SectionPos pos) {
            }
        };
        LevelLightEngine engine = new LevelLightEngine(getter, true, true);
        for (LightLayer layer : LightLayer.values()) {
            LightEngine<?, ?> e = (LightEngine<?, ?>) engine.getLayerListener(layer);
            for (String name : List.of("increaseQueue", "decreaseQueue")) {
                Field f = LightEngine.class.getDeclaredField(name);
                f.setAccessible(true);
                assertTrue(f.get(e) instanceof LightQueue, layer + " " + name + " is " + f.get(e).getClass().getName());
            }
        }
    }

    @Test
    void splinePointsMappedWithoutAStream() {
        TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L);
        CubicSpline<Float, BoundedFloatFunction<Float>> inner = CubicSpline.<Float, BoundedFloatFunction<Float>>builder(BoundedFloatFunction.IDENTITY)
            .addPoint(-1.0F, 0.5F).addPoint(1.0F, 2.0F).build();
        CubicSpline<Float, BoundedFloatFunction<Float>> spline = CubicSpline.<Float, BoundedFloatFunction<Float>>builder(BoundedFloatFunction.IDENTITY)
            .addPoint(-1.0F, inner).addPoint(0.0F, 1.0F).addPoint(1.0F, inner).build();
        CubicSpline<Float, BoundedFloatFunction<Float>> mapped = spline.mapAll(c -> c);
        List<?> values = ((CubicSpline.Multipoint<?, ?>) mapped).values();
        // Stream.toList's list is java.util.ImmutableCollections$ListN
        assertFalse(values.getClass().getName().startsWith("java.util.ImmutableCollections"), "values list " + values.getClass().getName());
        for (float x = -2.0F; x <= 2.0F; x += 0.125F) {
            assertTrue(Float.floatToIntBits(spline.apply(x)) == Float.floatToIntBits(mapped.apply(x)), "mapped spline at " + x);
        }
        assertTrue(spline.minValue() == mapped.minValue() && spline.maxValue() == mapped.maxValue(), "bounds");
    }

    @Test
    void poiSkipLeavesOutOnlyWritesWithoutPoiTypes() {
        TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L);
        assertTrue(HookTargets.allowed(HookTargets.STATE_POI_SKIP), "no other mod changes the POI bookkeeping here");
        assertTrue(Arrays.stream(WorldGenRegion.class.getDeclaredMethods()).anyMatch(m -> m.getName().endsWith("ga2$skipWithoutPoi")),
            "the wrapper of the call in WorldGenRegion.setBlock is in place");
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState granite = Blocks.GRANITE.defaultBlockState();
        BlockState bell = Blocks.BELL.defaultBlockState();
        assertTrue(PoiTypes.forState(bell).isPresent(), "a bell is a meeting point");
        for (int round = 0; round < 2; round++) {
            // the second round reads the answers the states keep
            assertTrue(PoiStates.skip(null, stone, granite), "stone to granite changes no POI");
            assertFalse(PoiStates.skip(null, stone, bell), "placing a bell adds a POI");
            assertFalse(PoiStates.skip(null, bell, stone), "replacing a bell removes one");
        }
        PoiStates.serverStarting();
        assertFalse(PoiStates.skip(null, bell, granite), "answers found again after a server start");
    }
    @Test
    void protoChunkReadsAreVanillas() throws Exception {
        int fluids = 0;
        // filled chunks: stone, caves, water and lava, air above
        for (int[] at : new int[][] { { 3, -7 }, { 40, 12 }, { -60, 25 }, { 0, 0 } }) {
        ProtoChunk chunk = TerrainHarness.site(
            TerrainHarness.Preset.OVERWORLD, 1234567L, at[0], at[1]).center();
        Field bounds = ProtoChunk.class.getDeclaredField("ga2$bounds");
        bounds.setAccessible(true);
        assertTrue(bounds.getLong(chunk) == 0L, "bounds not read yet");
        int min = chunk.getMinY();
        int max = chunk.getMaxY() + 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = min - 2; y < max + 2; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    pos.set(chunk.getPos().getMinBlockX() + x, y, chunk.getPos().getMinBlockZ() + z);
                    BlockState expected;
                    FluidState expectedFluid;
                    if (y < min || y >= max) {
                        expected = Blocks.VOID_AIR.defaultBlockState();
                        expectedFluid = Fluids.EMPTY.defaultFluidState();
                    } else {
                        LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
                        expected = section.hasOnlyAir() ? Blocks.AIR.defaultBlockState() : section.getBlockState(x, y & 15, z);
                        expectedFluid = section.hasOnlyAir() ? Fluids.EMPTY.defaultFluidState() : section.getFluidState(x, y & 15, z);
                    }
                    assertTrue(chunk.getBlockState(pos) == expected, "block at " + pos);
                    assertTrue(chunk.getFluidState(pos) == expectedFluid, "fluid at " + pos);
                    if (!expectedFluid.isEmpty()) {
                        fluids++;
                    }
                }
            }
        }
        assertTrue(bounds.getLong(chunk) != 0L, "the reads are GA2's (bounds kept)");
        }
        assertTrue(fluids > 0, "some chunk holds fluids");
    }

    /** {@code ChunkAccess.getMinY} and {@code getHeight}: the height accessor's answers, read once (the overwrite is in). */
    @Test
    void chunkHeightsAreTheAccessors() throws Exception {
        ProtoChunk chunk = TerrainHarness.site(
            TerrainHarness.Preset.OVERWORLD, 98765L, 5, -3).center();
        Field heights = ChunkAccess.class.getDeclaredField("ga2$heights");
        heights.setAccessible(true);
        Field accessorField = ChunkAccess.class.getDeclaredField("levelHeightAccessor");
        accessorField.setAccessible(true);
        LevelHeightAccessor accessor = (LevelHeightAccessor) accessorField.get(chunk);
        heights.setLong(chunk, 0L);
        assertTrue(chunk.getMinY() == accessor.getMinY(), "min build height");
        assertTrue(heights.getLong(chunk) != 0L, "the getters are GA2's (heights kept)");
        assertTrue(chunk.getHeight() == accessor.getHeight(), "height");
        assertTrue(chunk.getMaxY() + 1 == accessor.getMaxY() + 1, "max build height");
        assertTrue(chunk.getMinSectionY() == accessor.getMinSectionY() && chunk.getMaxSectionY() + 1 == accessor.getMaxSectionY() + 1
            && chunk.getSectionsCount() == accessor.getSectionsCount(), "sections");
        for (int y = accessor.getMinY() - 20; y < accessor.getMaxY() + 1 + 20; y++) {
            assertTrue(chunk.getSectionIndex(y) == accessor.getSectionIndex(y) && chunk.isOutsideBuildHeight(y) == accessor.isOutsideBuildHeight(y), "y " + y);
        }
    }
}
