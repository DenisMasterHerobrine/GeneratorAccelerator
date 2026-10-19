package dev.denismasterherobrine.generatoraccelerator2.mixin.structure;

import dev.denismasterherobrine.generatoraccelerator2.structure.PlacementRandoms;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * The frequency reducers of {@code StructurePlacement} on the thread's random source ({@link PlacementRandoms}) instead of a new one per
 * check. Seeding and draws are unchanged. Copied over vanilla's bodies when no other mod changes them ({@code compat.LateOverwrites}).
 */
@Mixin(value = StructurePlacement.class, priority = 5000)
public abstract class StructurePlacementRandomsMixin {
    @Unique
    private static boolean ga2$probabilityReducer(long seed, int salt, int x, int z, float probability) {
        WorldgenRandom worldgenrandom = PlacementRandoms.worldgen();
        worldgenrandom.setLargeFeatureWithSalt(seed, salt, x, z);
        return worldgenrandom.nextFloat() < probability;
    }

    @Unique
    private static boolean ga2$legacyProbabilityReducerWithDouble(long seed, int salt, int x, int z, float probability) {
        WorldgenRandom worldgenrandom = PlacementRandoms.worldgen();
        worldgenrandom.setLargeFeatureSeed(seed, x, z);
        return worldgenrandom.nextDouble() < (double) probability;
    }

    @Unique
    private static boolean ga2$legacyArbitrarySaltProbabilityReducer(long seed, int salt, int x, int z, float probability) {
        WorldgenRandom worldgenrandom = PlacementRandoms.worldgen();
        worldgenrandom.setLargeFeatureWithSalt(seed, x, z, 10387320);
        return worldgenrandom.nextFloat() < probability;
    }

    @Unique
    private static boolean ga2$legacyPillagerOutpostReducer(long seed, int salt, int x, int z, float probability) {
        int i = x >> 4;
        int j = z >> 4;
        WorldgenRandom worldgenrandom = PlacementRandoms.worldgen();
        worldgenrandom.setSeed((long) (i ^ j << 4) ^ seed);
        worldgenrandom.nextInt();
        return worldgenrandom.nextInt((int) (1.0F / probability)) == 0;
    }
}
