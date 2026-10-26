package dev.denismasterherobrine.generatoraccelerator2.features;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.features.placement.PatchRepeats;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.RandomPatchConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleBlockConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.placement.BlockPredicateFilter;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.EnvironmentScanPlacement;
import net.minecraft.world.level.levelgen.placement.HeightmapPlacement;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RarityFilter;
import org.junit.jupiter.api.Test;

/**
 * Random patches whose inner feature's modifiers only read ({@link PatchRepeats}): Stony Cliffs' patch (512 tries over 15 by 15
 * columns, a heightmap, a scan down for dirt, a filter of 18 block tests), one whose filter passes at most scans (many tries
 * place, so the earlier answers go stale often), and one whose inner feature draws (a rarity filter, never skipped). Each runs
 * on the vanilla path and on GA2's. The chunks, the random state and the side effects must be the same.
 */
public class PatchRepeatsTest {
    private static final long[] SEEDS = { 20261026L, 4242L, 99L };

    private static PlacedFeature stone(List<PlacementModifier> inner) {
        ConfiguredFeature<?, ?> block = new ConfiguredFeature<>(Feature.SIMPLE_BLOCK, new SimpleBlockConfiguration(BlockStateProvider.simple(Blocks.STONE)));
        PlacedFeature tried = new PlacedFeature(Holder.direct(block), inner);
        ConfiguredFeature<?, ?> patch = new ConfiguredFeature<>(Feature.RANDOM_PATCH, new RandomPatchConfiguration(512, 8, 0, Holder.direct(tried)));
        return new PlacedFeature(Holder.direct(patch), List.of(CountPlacement.of(6), InSquarePlacement.spread(),
            HeightmapPlacement.onHeightmap(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES)));
    }

    private static BlockPredicate cliffNeighbours() {
        List<BlockPredicate> any = new ArrayList<>();
        any.add(BlockPredicate.matchesTag(new Vec3i(0, 2, 0), BlockTags.MOSS_REPLACEABLE));
        any.add(BlockPredicate.matchesTag(new Vec3i(0, 1, 0), BlockTags.BASE_STONE_OVERWORLD));
        for (int[] o : new int[][] { { 1, -1 }, { -1, 1 }, { -1, -1 }, { 1, 1 }, { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) {
            any.add(BlockPredicate.matchesTag(new Vec3i(o[0], 2, o[1]), BlockTags.BASE_STONE_OVERWORLD));
        }
        for (int[] o : new int[][] { { 1, 1 }, { -1, -1 }, { 1, -1 }, { -1, 1 }, { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) {
            any.add(BlockPredicate.matchesTag(new Vec3i(o[0], 3, o[1]), BlockTags.MOSS_REPLACEABLE));
        }
        return BlockPredicate.allOf(BlockPredicate.anyOf(any));
    }

    @Test
    void sameAsVanilla() {
        HarnessWorld.Registries2 r = HarnessWorld.registries();
        BlockPredicate below = BlockPredicate.allOf(BlockPredicate.not(BlockPredicate.matchesTag(new Vec3i(0, -1, 0), BlockTags.TERRACOTTA)),
            BlockPredicate.matchesTag(new Vec3i(0, -1, 0), BlockTags.MOSS_REPLACEABLE));
        PlacementModifier scan = EnvironmentScanPlacement.scanningFor(Direction.DOWN, BlockPredicate.matchesTag(BlockTags.DIRT), 12);
        PlacementModifier height = HeightmapPlacement.onHeightmap(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
        List<PlacedFeature> features = List.of(
            stone(List.of(height, scan, BlockPredicateFilter.forPredicate(cliffNeighbours()), BlockPredicateFilter.forPredicate(below))),
            stone(List.of(height, scan, BlockPredicateFilter.forPredicate(below))),
            stone(List.of(height, RarityFilter.onAverageOnceEvery(3), scan)));
        Holder<net.minecraft.world.level.biome.Biome> plains = r.biomes.getHolderOrThrow(Biomes.PLAINS);
        List<String> failures = new ArrayList<>();
        long skipped0 = PatchRepeats.SKIPPED.sum();
        int compared = 0, placed = 0;
        for (int i = 0; i < features.size(); i++) {
            FeatureRun.Case c = new FeatureRun.Case("patch " + i, Holder.direct(features.get(i)), i, plains, plains, HarnessWorld.Kind.OVERWORLD);
            for (long seed : SEEDS) {
                FeatureRun.Result vanilla = FeatureRun.run(r, c, seed, true, false);
                assertTrue(vanilla.error() == null, "vanilla path threw " + vanilla.error());
                FeatureRun.Result fast = FeatureRun.run(r, c, seed, false, true);
                assertTrue(fast.error() == null, "GA2's path threw " + fast.error());
                List<String> diff = FeatureRun.diff(vanilla, fast);
                if (!diff.isEmpty()) {
                    failures.add(c.name() + " seed " + seed + ":\n    " + String.join("\n    ", diff));
                }
                compared++;
                placed += vanilla.placed() ? 1 : 0;
            }
        }
        long skipped = PatchRepeats.SKIPPED.sum() - skipped0;
        System.out.println("PatchRepeatsTest: " + compared + " runs compared, " + placed + " placed; tries answered from earlier ones " + skipped);
        assertTrue(failures.isEmpty(), failures.size() + " mismatches:\n" + String.join("\n", failures));
        assertTrue(placed > 0, "no patch placed anything");
        assertTrue(skipped > 1000, "too few tries answered from earlier ones: " + skipped);
    }
}
