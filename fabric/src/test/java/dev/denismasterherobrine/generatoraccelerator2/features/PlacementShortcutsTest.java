package dev.denismasterherobrine.generatoraccelerator2.features;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;
import net.minecraft.world.level.levelgen.placement.RepeatingPlacement;
import org.junit.jupiter.api.Test;

/**
 * Placement modifiers of classes the pipeline does not transcribe, as mods write them: a filter, a repeat, a repeat with
 * its own {@code getPositions}, and a plain modifier. The pipeline (filters asked {@code shouldPlace}, repeats asked
 * {@code count}) must reach the feature at the same positions, in the same order, with the same random calls, as
 * vanilla's stream chain.
 */
public class PlacementShortcutsTest {
    /** Keeps three positions in four, drawing from the random source. */
    static final class ModFilter extends PlacementFilter {
        @Override
        protected boolean shouldPlace(PlacementContext context, RandomSource random, BlockPos pos) {
            return random.nextInt(4) != 0;
        }

        @Override
        public PlacementModifierType<?> type() {
            return PlacementModifierType.RARITY_FILTER;
        }
    }

    /** Repeats the position 0 to 3 times. */
    static final class ModRepeat extends RepeatingPlacement {
        @Override
        protected int count(RandomSource random, BlockPos pos) {
            return random.nextInt(4);
        }

        @Override
        public PlacementModifierType<?> type() {
            return PlacementModifierType.COUNT;
        }
    }

    /** A repeat with its own getPositions (moves each copy): must keep running its own code. */
    static final class ModRepeatOwnPositions extends RepeatingPlacement {
        @Override
        protected int count(RandomSource random, BlockPos pos) {
            return 1 + random.nextInt(3);
        }

        @Override
        public Stream<BlockPos> getPositions(PlacementContext context, RandomSource random, BlockPos pos) {
            int n = this.count(random, pos);
            List<BlockPos> out = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                out.add(pos.offset(random.nextInt(8), 0, i));
            }
            return out.stream();
        }

        @Override
        public PlacementModifierType<?> type() {
            return PlacementModifierType.COUNT;
        }
    }

    /** A plain modifier: two spread positions. */
    static final class ModSpread extends PlacementModifier {
        @Override
        public Stream<BlockPos> getPositions(PlacementContext context, RandomSource random, BlockPos pos) {
            return Stream.of(pos.east(random.nextInt(5)), pos.north(random.nextInt(5)));
        }

        @Override
        public PlacementModifierType<?> type() {
            return PlacementModifierType.RANDOM_OFFSET;
        }
    }

    /** Records where it is placed (and draws once, so the random sequence after it is part of the comparison). */
    static final class Recorder extends Feature<NoneFeatureConfiguration> {
        final List<BlockPos> placed = new ArrayList<>();

        Recorder() {
            super(NoneFeatureConfiguration.CODEC);
        }

        @Override
        public boolean place(NoneFeatureConfiguration config, WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
            this.placed.add(origin.offset(0, random.nextInt(3), 0));
            return true;
        }

        @Override
        public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
            return false;
        }
    }

    @Test
    void sameAsVanillaStreams() {
        TerrainHarness.World world = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L);
        WorldGenLevel level = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[] { WorldGenLevel.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "getMinBuildHeight" -> world.heights.getMinBuildHeight();
                case "getHeight" -> args == null || args.length == 0 ? world.heights.getHeight() : 0;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "test level";
                default -> throw new UnsupportedOperationException(method.getName());
            });
        List<List<PlacementModifier>> chains = List.of(
            List.of(new ModFilter()),
            List.of(new ModRepeat(), new ModFilter()),
            List.of(new ModSpread(), new ModRepeat(), new ModFilter(), new ModRepeat()),
            List.of(new ModRepeatOwnPositions(), new ModFilter(), new ModSpread()),
            List.of(new ModRepeat(), new ModRepeatOwnPositions(), new ModRepeat(), new ModFilter()));
        long filters0 = FeatureStats.PLACEMENTS_GENERIC_FILTERS.sum();
        long repeats0 = FeatureStats.PLACEMENTS_GENERIC_REPEATS.sum();
        long streamed0 = FeatureStats.PLACEMENTS_GENERIC_MODIFIERS.sum();
        int compared = 0;
        for (List<PlacementModifier> chain : chains) {
            for (long seed = 0; seed < 200; seed++) {
                Recorder vanillaFeature = new Recorder();
                Recorder fastFeature = new Recorder();
                PlacedFeature vanillaPlaced = new PlacedFeature(Holder.direct(new ConfiguredFeature<>(vanillaFeature, NoneFeatureConfiguration.INSTANCE)), chain);
                PlacedFeature fastPlaced = new PlacedFeature(Holder.direct(new ConfiguredFeature<>(fastFeature, NoneFeatureConfiguration.INSTANCE)), chain);
                RandomSource vanillaRandom = RandomSource.create(seed);
                RandomSource fastRandom = RandomSource.create(seed);
                BlockPos origin = new BlockPos((int) seed * 16, 64, -(int) seed * 16);
                boolean[] vanillaResult = new boolean[1];
                FeatureHooks.runVanilla(() -> vanillaResult[0] = vanillaPlaced.place(level, world.generator, vanillaRandom, origin));
                boolean fastResult = fastPlaced.place(level, world.generator, fastRandom, origin);
                assertEquals(vanillaResult[0], fastResult, "result, chain " + chain + " seed " + seed);
                assertEquals(vanillaFeature.placed, fastFeature.placed, "positions, chain " + chain + " seed " + seed);
                assertEquals(vanillaRandom.nextLong(), fastRandom.nextLong(), "random sequence after, chain " + chain + " seed " + seed);
                compared++;
            }
        }
        assertTrue(FeatureStats.PLACEMENTS_GENERIC_FILTERS.sum() > filters0, "filters went through shouldPlace");
        assertTrue(FeatureStats.PLACEMENTS_GENERIC_REPEATS.sum() > repeats0, "repeats went through count");
        assertTrue(FeatureStats.PLACEMENTS_GENERIC_MODIFIERS.sum() > streamed0, "a repeat with its own getPositions and the plain modifier used their streams");
        System.out.println("placement shortcuts: " + compared + " placements compared; filters " + (FeatureStats.PLACEMENTS_GENERIC_FILTERS.sum() - filters0)
            + ", repeats " + (FeatureStats.PLACEMENTS_GENERIC_REPEATS.sum() - repeats0) + ", streamed " + (FeatureStats.PLACEMENTS_GENERIC_MODIFIERS.sum() - streamed0));
    }

    /**
     * A mod's wrapper of the {@code forEach} that places at each position, written like Confluence's. The wrapper wraps the
     * consumer and draws from the placement's random. The pipeline hands the wrapper one position at a time. The reference
     * hands it vanilla's whole lazy stream. Same positions, same random sequence, same result.
     */
    @Test
    void positionWrapperSameAsWrappedForEach() {
        TerrainHarness.World world = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L);
        WorldGenLevel level = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[] { WorldGenLevel.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "getMinBuildHeight" -> world.heights.getMinBuildHeight();
                case "getHeight" -> args == null || args.length == 0 ? world.heights.getHeight() : 0;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "test level";
                default -> throw new UnsupportedOperationException(method.getName());
            });
        com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> forEach = args -> {
            @SuppressWarnings("unchecked")
            Stream<BlockPos> s = (Stream<BlockPos>) args[0];
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<BlockPos> c = (java.util.function.Consumer<BlockPos>) args[1];
            s.forEach(c);
            return null;
        };
        // the mod's handler: a third of the positions are "replaced" (drawing from the random source), the rest placed
        interface Handler {
            void wrap(Stream<BlockPos> stream, java.util.function.Consumer<BlockPos> consumer, com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original,
                      PlacementContext ctx, RandomSource random, org.apache.commons.lang3.mutable.MutableBoolean success);
        }
        Handler handler = (stream, consumer, original, ctx, random, success) -> original.call(stream, (java.util.function.Consumer<BlockPos>) pos -> {
            if (random.nextInt(3) == 0) {
                success.setTrue();
            } else {
                consumer.accept(pos);
            }
        });
        List<List<PlacementModifier>> chains = List.of(
            List.of(new ModRepeat(), new ModFilter()),
            List.of(new ModSpread(), new ModRepeat(), new ModFilter(), new ModRepeat()),
            List.of(new ModRepeatOwnPositions(), new ModFilter(), new ModSpread()));
        int compared = 0;
        dev.denismasterherobrine.generatoraccelerator2.features.placement.PlacementPipeline.position =
            (placed, pos, place, ctx, random, success) -> handler.wrap(Stream.of(pos), place, forEach, ctx, random, success);
        try {
            for (List<PlacementModifier> chain : chains) {
                for (long seed = 0; seed < 200; seed++) {
                    Recorder vanillaFeature = new Recorder();
                    Recorder fastFeature = new Recorder();
                    ConfiguredFeature<?, ?> vanillaConfigured = new ConfiguredFeature<>(vanillaFeature, NoneFeatureConfiguration.INSTANCE);
                    PlacedFeature fastPlaced = new PlacedFeature(Holder.direct(new ConfiguredFeature<>(fastFeature, NoneFeatureConfiguration.INSTANCE)), chain);
                    RandomSource vanillaRandom = RandomSource.create(seed);
                    RandomSource fastRandom = RandomSource.create(seed);
                    BlockPos origin = new BlockPos((int) seed * 16, 64, -(int) seed * 16);
                    // vanilla's placeWithContext, with the handler around its forEach
                    PlacementContext ctx = new PlacementContext(level, world.generator, java.util.Optional.empty());
                    Stream<BlockPos> stream = Stream.of(origin);
                    for (PlacementModifier m : chain) {
                        stream = stream.flatMap(p -> m.getPositions(ctx, vanillaRandom, p));
                    }
                    org.apache.commons.lang3.mutable.MutableBoolean success = new org.apache.commons.lang3.mutable.MutableBoolean();
                    handler.wrap(stream, p -> {
                        if (vanillaConfigured.place(level, world.generator, vanillaRandom, p)) {
                            success.setTrue();
                        }
                    }, forEach, ctx, vanillaRandom, success);
                    boolean fastResult = fastPlaced.place(level, world.generator, fastRandom, origin);
                    assertEquals(success.isTrue(), fastResult, "result, chain " + chain + " seed " + seed);
                    assertEquals(vanillaFeature.placed, fastFeature.placed, "positions, chain " + chain + " seed " + seed);
                    assertEquals(vanillaRandom.nextLong(), fastRandom.nextLong(), "random sequence after, chain " + chain + " seed " + seed);
                    compared++;
                }
            }
        } finally {
            dev.denismasterherobrine.generatoraccelerator2.features.placement.PlacementPipeline.position = null;
        }
        System.out.println("placement position wrapper: " + compared + " placements compared");
    }
}
