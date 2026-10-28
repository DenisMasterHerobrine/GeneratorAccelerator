package dev.denismasterherobrine.generatoraccelerator2.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.util.Optional;
import java.util.Random;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.junit.jupiter.api.Test;

/**
 * The structure placement checks on the thread's reused random source ({@link PlacementRandoms}, put over vanilla's bodies by
 * {@code compat.LateOverwrites}) give what vanilla's bodies give with a new source per call: the potential chunk of random spread
 * placements of both spread types, and every frequency reducer's answer, over random seeds, salts, spacings and chunks.
 */
public class PlacementRandomsTest {
    @Test
    void spreadAndReducersMatchFreshSources() {
        TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L); // boots the game (registries, mixins)
        Random r = new Random(20261012L);
        long before = PlacementRandoms.worldgen().getCount();
        int reducers = 0;
        for (int t = 0; t < 20000; t++) {
            int spacing = 2 + r.nextInt(120);
            int separation = r.nextInt(spacing);
            RandomSpreadType type = r.nextBoolean() ? RandomSpreadType.LINEAR : RandomSpreadType.TRIANGULAR;
            int salt = r.nextInt();
            long seed = r.nextLong();
            int x = r.nextInt(1 << 22) - (1 << 21), z = r.nextInt(1 << 22) - (1 << 21);
            StructurePlacement.FrequencyReductionMethod method = StructurePlacement.FrequencyReductionMethod.values()[r.nextInt(4)];
            float frequency = r.nextFloat() * 0.99F + 0.005F;
            RandomSpreadStructurePlacement p = new RandomSpreadStructurePlacement(Vec3i.ZERO, method, frequency, salt, Optional.empty(), spacing, separation, type);
            assertEquals(potentialChunk(seed, x, z, spacing, separation, type, salt), p.getPotentialStructureChunk(seed, x, z),
                "potential chunk: seed " + seed + " at " + x + ", " + z + ", spacing " + spacing + ", separation " + separation + ", " + type + ", salt " + salt);
            assertEquals(reduce(method, seed, salt, x, z, frequency), method.shouldGenerate(seed, salt, x, z, frequency),
                "reducer " + method + ": seed " + seed + " at " + x + ", " + z + ", salt " + salt + ", frequency " + frequency);
            reducers++;
        }
        // the checks ran on the thread's source (else this compared vanilla's bodies with themselves)
        assertTrue(PlacementRandoms.worldgen().getCount() - before >= 2L * reducers, "draws on the thread's source: " + (PlacementRandoms.worldgen().getCount() - before));
    }

    /** {@code RandomSpreadStructurePlacement.getPotentialStructureChunk}, vanilla's body. */
    private static ChunkPos potentialChunk(long seed, int x, int z, int spacing, int separation, RandomSpreadType type, int salt) {
        int i = Math.floorDiv(x, spacing);
        int j = Math.floorDiv(z, spacing);
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        random.setLargeFeatureWithSalt(seed, i, j, salt);
        int k = spacing - separation;
        int l = type.evaluate(random, k);
        int i1 = type.evaluate(random, k);
        return new ChunkPos(i * spacing + l, j * spacing + i1);
    }

    /** {@code StructurePlacement}'s reducers, vanilla's bodies. */
    private static boolean reduce(StructurePlacement.FrequencyReductionMethod method, long seed, int salt, int x, int z, float probability) {
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        switch (method) {
            case DEFAULT -> {
                random.setLargeFeatureWithSalt(seed, salt, x, z);
                return random.nextFloat() < probability;
            }
            case LEGACY_TYPE_1 -> {
                int i = x >> 4;
                int j = z >> 4;
                random.setSeed((long) (i ^ j << 4) ^ seed);
                random.nextInt();
                return random.nextInt((int) (1.0F / probability)) == 0;
            }
            case LEGACY_TYPE_2 -> {
                random.setLargeFeatureWithSalt(seed, x, z, 10387320);
                return random.nextFloat() < probability;
            }
            default -> {
                random.setLargeFeatureSeed(seed, x, z);
                return random.nextDouble() < (double) probability;
            }
        }
    }
}
