package dev.denismasterherobrine.generatoraccelerator2.features;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.features.predicate.PredicatePrograms;
import dev.denismasterherobrine.generatoraccelerator2.testutil.VanillaTags;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.placement.BlockPredicateFilter;
import net.minecraft.world.level.levelgen.placement.EnvironmentScanPlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.Test;

/**
 * Every block predicate a vanilla placed feature uses (block predicate filters, environment scans) and a few thousand random
 * trees over every vanilla predicate kind give the same answer through {@link PredicatePrograms} as through their own
 * {@code test}. Positions lie all over a harness region (terrain, caves, sky, below and above the build height), and each
 * is asked as an immutable and as a mutable position. Fixed block lists of several sizes (identity checks), tags and
 * repeated offsets (remembered reads) are included.
 */
public class PredicateProgramsTest {
    private static HarnessLevel level(HarnessWorld.Registries2 r) {
        HarnessWorld.Kind kind = HarnessWorld.Kind.OVERWORLD;
        LevelHeightAccessor heights = HarnessWorld.heights(r, kind);
        ProtoChunk[] chunks = HarnessWorld.clone(r, FeatureRun.terrain(r, kind, 1234567L), heights);
        Holder<Biome> plains = r.biomes.getHolderOrThrow(Biomes.PLAINS);
        for (ProtoChunk c : chunks) {
            c.fillBiomesFromNoise((x, y, z, sampler) -> plains, Climate.empty());
        }
        return new HarnessLevel(chunks, HarnessWorld.RADIUS, FeatureRun.CENTER_X, FeatureRun.CENTER_Z, heights,
            HarnessWorld.dimension(r, kind), 1234567L, HarnessWorld.randomState(r, kind, 1234567L), r.access, false);
    }

    /**
     * {@code gradlew test --tests "*PredicateProgramsTest*" -Dga2.bench=true}: ns per predicate test, vanilla's against the
     * programs, for the vanilla placed features' predicates at positions around the terrain surface (where placements ask),
     * single-threaded, alternating rounds.
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "ga2.bench", matches = "true")
    void bench() throws Exception {
        VanillaTags.bind();
        HarnessWorld.Registries2 r = HarnessWorld.registries();
        HarnessLevel level = level(r);
        List<BlockPredicate> predicates = vanillaPredicates(r);
        Random random = new Random(7);
        BlockPos[] positions = new BlockPos[4096];
        for (int i = 0; i < positions.length; i++) {
            int x = FeatureRun.CENTER_X * 16 + random.nextInt(16);
            int z = FeatureRun.CENTER_Z * 16 + random.nextInt(16);
            int surface = level.getChunk(x >> 4, z >> 4).getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, x & 15, z & 15);
            positions[i] = new BlockPos(x, surface - 4 + random.nextInt(9), z);
        }
        Object[] programs = new Object[predicates.size()];
        for (int i = 0; i < programs.length; i++) {
            programs[i] = PredicatePrograms.programFor(predicates.get(i));
        }
        int rounds = Integer.getInteger("ga2.bench.rounds", 12);
        long sink = 0;
        double bestVanilla = Double.MAX_VALUE, bestProgram = Double.MAX_VALUE;
        for (int round = 0; round < rounds; round++) {
            long t0 = System.nanoTime();
            for (BlockPredicate p : predicates) {
                for (BlockPos pos : positions) {
                    sink += p.test(level, pos) ? 1 : 0;
                }
            }
            long t1 = System.nanoTime();
            for (int i = 0; i < programs.length; i++) {
                BlockPredicate p = predicates.get(i);
                Object program = programs[i];
                for (BlockPos pos : positions) {
                    sink += PredicatePrograms.test(program, p, level, pos) ? 1 : 0;
                }
            }
            long t2 = System.nanoTime();
            double n = (double) predicates.size() * positions.length;
            bestVanilla = Math.min(bestVanilla, (t1 - t0) / n);
            bestProgram = Math.min(bestProgram, (t2 - t1) / n);
            System.out.printf("predicate bench round %d: vanilla %.1f ns, programs %.1f ns per test%n", round, (t1 - t0) / n, (t2 - t1) / n);
        }
        System.out.printf("predicate bench: %d predicates, best vanilla %.1f ns, best programs %.1f ns per test (%d); %s%n",
            predicates.size(), bestVanilla, bestProgram, sink, PredicatePrograms.codegenStats());
        // per predicate, after the rounds above warmed everything up
        for (int i = 0; i < programs.length; i++) {
            BlockPredicate p = predicates.get(i);
            Object program = programs[i];
            long bv = Long.MAX_VALUE, bp = Long.MAX_VALUE;
            for (int round = 0; round < 5; round++) {
                long t0 = System.nanoTime();
                for (BlockPos pos : positions) {
                    sink += p.test(level, pos) ? 1 : 0;
                }
                long t1 = System.nanoTime();
                for (BlockPos pos : positions) {
                    sink += PredicatePrograms.test(program, p, level, pos) ? 1 : 0;
                }
                long t2 = System.nanoTime();
                bv = Math.min(bv, t1 - t0);
                bp = Math.min(bp, t2 - t1);
            }
            String s = p.toString();
            System.out.printf("predicate bench each: vanilla %6.1f programs %6.1f ns  %s%n", bv / (double) positions.length, bp / (double) positions.length,
                (program.getClass().getSimpleName() + " ")
                    + p.getClass().getSimpleName() + " " + s.substring(0, Math.min(90, s.length())));
        }
    }

    @Test
    void sameAnswersAsVanilla() throws Exception {
        VanillaTags.bind();
        HarnessWorld.Registries2 r = HarnessWorld.registries();
        LevelHeightAccessor heights = HarnessWorld.heights(r, HarnessWorld.Kind.OVERWORLD);
        HarnessLevel level = level(r);

        List<BlockPredicate> predicates = new ArrayList<>(vanillaPredicates(r));
        int vanillaCount = predicates.size();
        Random random = new Random(42);
        for (int i = 0; i < 3000; i++) {
            predicates.add(randomTree(random, 0));
        }

        long compiledBefore = PredicatePrograms.COMPILED.sum();
        int asked = 0;
        int x0 = FeatureRun.CENTER_X * 16;
        int z0 = FeatureRun.CENTER_Z * 16;
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        for (int p = 0; p < predicates.size(); p++) {
            BlockPredicate predicate = predicates.get(p);
            Object compiled = PredicatePrograms.programFor(predicate);
            for (int k = 0; k < 120; k++) {
                int x = x0 + random.nextInt(16);
                int z = z0 + random.nextInt(16);
                int y = heights.getMinBuildHeight() - 3 + random.nextInt(heights.getHeight() + 6);
                BlockPos pos = new BlockPos(x, y, z);
                boolean vanilla = predicate.test(level, pos);
                boolean program = PredicatePrograms.test(compiled, predicate, level, pos);
                assertTrue(vanilla == program, (p < vanillaCount ? "vanilla" : "random") + " predicate " + p + " " + predicate + " at " + pos
                    + ": vanilla " + vanilla + ", program " + program);
                mutable.set(x, y, z);
                assertTrue(predicate.test(level, mutable) == PredicatePrograms.test(compiled, predicate, level, mutable), "mutable position, predicate " + p);
                asked++;
            }
        }
        assertTrue(vanillaCount > 10, "vanilla predicates: " + vanillaCount);
        assertTrue(PredicatePrograms.COMPILED.sum() - compiledBefore > predicates.size() / 2,
            "programs compiled: " + (PredicatePrograms.COMPILED.sum() - compiledBefore) + " of " + predicates.size());
        assertTrue(asked > 100000, "asked " + asked);
    }

    /** The predicates of every vanilla placed feature's block predicate filters and environment scans. */
    private static List<BlockPredicate> vanillaPredicates(HarnessWorld.Registries2 r) throws Exception {
        Field target = EnvironmentScanPlacement.class.getDeclaredField("targetCondition");
        Field allowed = EnvironmentScanPlacement.class.getDeclaredField("allowedSearchCondition");
        target.setAccessible(true);
        allowed.setAccessible(true);
        List<BlockPredicate> out = new ArrayList<>();
        for (Holder.Reference<PlacedFeature> pf : r.lookup.lookupOrThrow(Registries.PLACED_FEATURE).listElements().toList()) {
            for (PlacementModifier m : pf.value().placement()) {
                if (m instanceof BlockPredicateFilter f) {
                    out.add(f.predicate);
                } else if (m instanceof EnvironmentScanPlacement e) {
                    out.add((BlockPredicate) target.get(e));
                    out.add((BlockPredicate) allowed.get(e));
                }
            }
        }
        return out;
    }

    private static final Block[] BLOCKS = { Blocks.AIR, Blocks.STONE, Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.WATER, Blocks.SAND, Blocks.GRAVEL,
        Blocks.DEEPSLATE, Blocks.BEDROCK, Blocks.CAVE_AIR, Blocks.OAK_LOG, Blocks.SHORT_GRASS, Blocks.SNOW, Blocks.LAVA, Blocks.ANDESITE };

    private static BlockPredicate randomTree(Random random, int depth) {
        int kind = depth >= 3 ? 3 + random.nextInt(9) : random.nextInt(12);
        // few distinct offsets, so trees often read the same block twice
        Vec3i offset = new Vec3i(random.nextInt(3) - 1, random.nextInt(5) - 2, random.nextInt(3) - 1);
        return switch (kind) {
            case 0 -> BlockPredicate.allOf(children(random, depth));
            case 1 -> BlockPredicate.anyOf(children(random, depth));
            case 2 -> BlockPredicate.not(randomTree(random, depth + 1));
            case 3 -> BlockPredicate.matchesBlocks(offset, pickBlocks(random, 1));
            case 4 -> BlockPredicate.matchesBlocks(offset, pickBlocks(random, 2 + random.nextInt(5)));
            case 5 -> BlockPredicate.matchesBlocks(offset, pickBlocks(random, 9 + random.nextInt(5)));
            case 6 -> BlockPredicate.matchesTag(offset, random.nextBoolean() ? BlockTags.LOGS : BlockTags.DIRT);
            case 7 -> BlockPredicate.matchesFluids(offset, random.nextBoolean() ? Fluids.WATER : Fluids.LAVA);
            case 8 -> random.nextBoolean() ? BlockPredicate.solid(offset) : BlockPredicate.replaceable(offset);
            case 9 -> BlockPredicate.wouldSurvive(Blocks.SHORT_GRASS.defaultBlockState(), offset);
            case 10 -> random.nextBoolean() ? BlockPredicate.hasSturdyFace(offset, Direction.UP) : BlockPredicate.insideWorld(offset);
            default -> switch (random.nextInt(3)) {
                case 0 -> BlockPredicate.alwaysTrue();
                case 1 -> BlockPredicate.noFluid(offset);
                default -> BlockPredicate.unobstructed(offset);
            };
        };
    }

    private static List<BlockPredicate> children(Random random, int depth) {
        List<BlockPredicate> out = new ArrayList<>();
        int n = 1 + random.nextInt(4);
        for (int i = 0; i < n; i++) {
            out.add(randomTree(random, depth + 1));
        }
        return out;
    }

    private static List<Block> pickBlocks(Random random, int n) {
        List<Block> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(random.nextInt(4) == 0 ? BuiltInRegistries.BLOCK.byId(random.nextInt(BuiltInRegistries.BLOCK.size())) : BLOCKS[random.nextInt(BLOCKS.length)]);
        }
        return out;
    }
}
