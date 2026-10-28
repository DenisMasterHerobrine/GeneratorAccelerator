package dev.denismasterherobrine.generatoraccelerator2.features;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.features.region.RegionWriter;
import dev.denismasterherobrine.generatoraccelerator2.features.verify.FeatureCompare;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.junit.jupiter.api.Test;

/**
 * {@code RegionWriter.setBlock} must behave exactly like {@code WorldGenRegion.setBlock} (as transcribed
 * in the harness level): return value, block, section counters, the four final heightmaps including
 * their lazy priming, block entity NBT, post-processing marks, POI records. This also holds for writes
 * outside the write radius and the build height, and for a neighbour already past INITIALIZE_LIGHT.
 */
public class RegionWriterTest {
    @Test
    void writesMatchVanilla() {
        HarnessWorld.Registries2 r = HarnessWorld.registries();
        long seed = 4242L;
        LevelHeightAccessor heights = HarnessWorld.heights(r, HarnessWorld.Kind.OVERWORLD);
        ProtoChunk[] base = FeatureRun.terrain(r, HarnessWorld.Kind.OVERWORLD, seed);
        ProtoChunk[] a = HarnessWorld.clone(r, base, heights);
        ProtoChunk[] b = HarnessWorld.clone(r, base, heights);
        HarnessLevel vanilla = new HarnessLevel(a, HarnessWorld.RADIUS, FeatureRun.CENTER_X, FeatureRun.CENTER_Z, heights,
            HarnessWorld.dimension(r, HarnessWorld.Kind.OVERWORLD), seed, HarnessWorld.randomState(r, HarnessWorld.Kind.OVERWORLD, seed), r.access, false);
        HarnessLevel fast = new HarnessLevel(b, HarnessWorld.RADIUS, FeatureRun.CENTER_X, FeatureRun.CENTER_Z, heights,
            HarnessWorld.dimension(r, HarnessWorld.Kind.OVERWORLD), seed, HarnessWorld.randomState(r, HarnessWorld.Kind.OVERWORLD, seed), r.access, true);

        BlockState[] states = {
            Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.WATER.defaultBlockState(),
            Blocks.LAVA.defaultBlockState(), Blocks.OAK_LOG.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState().setValue(BlockStateProperties.DISTANCE, 3),
            Blocks.CHEST.defaultBlockState(), Blocks.SPAWNER.defaultBlockState(), Blocks.SNOW.defaultBlockState(), Blocks.KELP_PLANT.defaultBlockState(),
            Blocks.GRASS.defaultBlockState(), Blocks.GLOW_LICHEN.defaultBlockState(), Blocks.BEE_NEST.defaultBlockState(), Blocks.DIRT.defaultBlockState(),
            Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.MOSS_BLOCK.defaultBlockState(), Blocks.AMETHYST_BLOCK.defaultBlockState(),
            Blocks.LIGHTNING_ROD.defaultBlockState(), Blocks.VINE.defaultBlockState(), Blocks.TALL_GRASS.defaultBlockState()
        };
        Random rng = new Random(seed);
        List<String> mismatches = new ArrayList<>();
        int minY = heights.getMinBuildHeight();
        int maxY = heights.getMaxBuildHeight();
        for (int i = 0; i < 40000; i++) {
            int x = FeatureRun.CENTER_X * 16 + rng.nextInt(80) - 32;
            int z = FeatureRun.CENTER_Z * 16 + rng.nextInt(80) - 32;
            int y = rng.nextInt(100) < 3 ? (rng.nextBoolean() ? minY - 1 - rng.nextInt(5) : maxY + rng.nextInt(5)) : minY + rng.nextInt(maxY - minY);
            if (rng.nextInt(4) > 0) {
                y = 20 + rng.nextInt(90); // mostly around the surface so heightmaps move
            }
            BlockState state = states[rng.nextInt(states.length)];
            BlockPos pos = new BlockPos(x, y, z);
            boolean expected = vanilla.setBlock(pos, state, 3);
            boolean actual = RegionWriter.setBlock(fast, pos, state);
            if (expected != actual) {
                mismatches.add("write " + i + " at " + pos + " of " + state + ": expected " + expected + " got " + actual);
            }
        }
        assertTrue(mismatches.isEmpty(), String.join("\n", mismatches));
        assertEquals(vanilla.poiChanges(), fast.poiChanges(), "POI change records differ");
        List<String> diff = new ArrayList<>();
        for (int i = 0; i < a.length; i++) {
            diff.addAll(FeatureCompare.compare(a[i], b[i], 10));
        }
        assertTrue(diff.isEmpty(), String.join("\n", diff));
    }
}
