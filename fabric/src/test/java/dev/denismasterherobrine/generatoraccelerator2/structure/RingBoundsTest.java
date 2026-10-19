package dev.denismasterherobrine.generatoraccelerator2.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.scheduling.RingSearches;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import org.junit.jupiter.api.Test;

/**
 * Every ring position vanilla's own computation ({@code ChunkGeneratorStructureState.generateRingPositions}) produces
 * lies outside {@link RingBounds#innerRadius}, for placements from tiny to huge distances, with the biome search finding
 * a preferred biome nearly everywhere (all overworld biomes preferred) and nowhere (none preferred, so the unmoved
 * position is kept), over several seeds.
 */
public class RingBoundsTest {
    private static final int[] DISTANCES = { 0, 1, 2, 3, 4, 5, 6, 8, 10, 16, 32, 64, 100, 400, 1023 };
    private static final long[] SEEDS = { 1234567L, -42L, 20261005L, 7L };

    @Test
    void ringPositionsStayOutsideTheInnerRadius() throws Exception {
        Constructor<ChunkGeneratorStructureState> ctor = ChunkGeneratorStructureState.class.getDeclaredConstructor(
            RandomState.class, BiomeSource.class, long.class, long.class, List.class);
        ctor.setAccessible(true);
        Method generate = ChunkGeneratorStructureState.class.getDeclaredMethod("generateRingPositions", Holder.class, ConcentricRingsStructurePlacement.class);
        generate.setAccessible(true);

        long checked = 0;
        double tightest = Double.MAX_VALUE;
        for (long seed : SEEDS) {
            TerrainHarness.World world = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, seed);
            var structures = world.registries.lookup.lookupOrThrow(Registries.STRUCTURE);
            HolderSet<Biome> every = HolderSet.direct(List.copyOf(world.biomeSource.possibleBiomes()));
            for (HolderSet<Biome> preferred : List.of(every, HolderSet.<Biome>direct())) {
                for (int distance : DISTANCES) {
                    ConcentricRingsStructurePlacement placement = new ConcentricRingsStructurePlacement(distance, 3, 128, preferred);
                    Holder<StructureSet> set = Holder.direct(new StructureSet(structures.getOrThrow(BuiltinStructures.STRONGHOLD), placement));
                    ChunkGeneratorStructureState state = ctor.newInstance(world.randomState, world.biomeSource, seed, seed, List.of(set));
                    @SuppressWarnings("unchecked")
                    CompletableFuture<List<ChunkPos>> future = (CompletableFuture<List<ChunkPos>>) generate.invoke(state, set, placement);
                    // the searches start one at a time beside chunk work. A wait for them gets the whole pool
                    RingSearches.hurry();
                    List<ChunkPos> positions = future.join();
                    assertEquals(128, positions.size());
                    double radius = RingBounds.innerRadius(placement);
                    for (ChunkPos p : positions) {
                        assertFalse(RingBounds.inside(radius, p.x, p.z, 0),
                            "seed " + seed + " distance " + distance + ": ring position " + p + " inside the inner radius " + radius);
                        if (radius > 0) {
                            tightest = Math.min(tightest, Math.sqrt((double) p.x * p.x + (double) p.z * p.z) - radius);
                        }
                        checked++;
                    }
                }
            }
        }
        assertTrue(checked > 10000, "too few positions: " + checked);
        System.out.println("RingBoundsTest: " + checked + " ring positions outside the inner radius; closest " + tightest + " chunks beyond it");
    }

    @Test
    void insideCoversEveryChunkOfTheSquare() {
        // the farthest chunk of the square around (x, z) decides
        assertTrue(RingBounds.inside(10.0, 3, 4, 0));
        assertTrue(RingBounds.inside(10.0, -3, -4, 2));
        assertFalse(RingBounds.inside(10.0, -3, -4, 4));
        assertFalse(RingBounds.inside(10.0, 0, 10, 0));
        assertFalse(RingBounds.inside(-1.0, 0, 0, 0));
        assertFalse(RingBounds.inside(100.0, 0, 0, Integer.MAX_VALUE));
    }
}
