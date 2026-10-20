package dev.denismasterherobrine.generatoraccelerator2.features.fast;

import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.NoiseSpec;
import dev.denismasterherobrine.generatoraccelerator2.features.FeatureStats;
import dev.denismasterherobrine.generatoraccelerator2.features.region.Ga2Region;
import dev.denismasterherobrine.generatoraccelerator2.features.region.SectionCursor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BuddingAmethystBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.GeodeBlockSettings;
import net.minecraft.world.level.levelgen.GeodeCrackSettings;
import net.minecraft.world.level.levelgen.GeodeLayerSettings;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.GeodeFeature;
import net.minecraft.world.level.levelgen.feature.configurations.GeodeConfiguration;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.material.FluidState;

/**
 * Exact {@code GeodeFeature.place}. The per-call noise only depends on the level seed, so it is
 * built once per seed. Distribution points keep their insertion order in plain arrays. Reads go
 * through the cursor, and writes go through the exact region writer. Scheduled ticks still receive
 * fresh immutable positions, as vanilla's do, because the tick keeps the object.
 */
public final class GeodeFast {
    private GeodeFast() {}

    private static final Direction[] DIRECTIONS = Direction.values();
    private static final Map<Long, Last> NOISE = new ConcurrentHashMap<>();

    /**
     * A seed's noise and its flattened form. The last seed's entry is looked up without boxing the seed or reading the map
     * (the server's levels share one seed).
     */
    private record Last(long seed, NormalNoise noise, NoiseSpec spec) {}

    private static volatile Last last;

    /** A server stopped. Each world adds its seed's entry, so the map is emptied between worlds. */
    public static void forgetSeeds() {
        NOISE.clear();
        last = null;
    }

    private static Last noise(long seed) {
        Last l = last;
        if (l != null && l.seed == seed) {
            return l;
        }
        l = NOISE.computeIfAbsent(seed, s -> {
            NormalNoise noise = NormalNoise.create(new WorldgenRandom(new LegacyRandomSource(s)), -4, 1.0);
            return new Last(s, noise, NoiseSpec.of(noise));
        });
        last = l;
        return l;
    }

    /** A thread's buffers for {@link #place}: a z slab's noise values, the points' distance terms of a block, a column of the noise. */
    private static final class Scratch {
        private static final ThreadLocal<Scratch> MINE = ThreadLocal.withInitial(Scratch::new);
        double[] slab = new double[0];
        double[] inv = new double[0];
        NoiseSpec spec;
        NoiseSpec.Column column;

        static Scratch of(NoiseSpec spec, int slabSize, int points) {
            Scratch s = MINE.get();
            if (s.slab.length < slabSize) {
                s.slab = new double[slabSize];
            }
            if (s.inv.length < points) {
                s.inv = new double[points];
            }
            if (s.spec != spec) {
                s.spec = spec;
                s.column = spec.newColumn();
            }
            return s;
        }
    }

    private static boolean nonNegativeAmplitudes(NormalNoise noise) {
        it.unimi.dsi.fastutil.doubles.DoubleList amplitudes = noise.parameters().amplitudes();
        for (int i = 0, n = amplitudes.size(); i < n; i++) {
            if (!(amplitudes.getDouble(i) >= 0.0)) {
                return false;
            }
        }
        return true;
    }

    public static boolean place(net.minecraft.world.level.levelgen.feature.Feature<?> feature, FeaturePlaceContext<?> ctx, Ga2Region region) {
        GeodeConfiguration config = (GeodeConfiguration) ctx.config();
        RandomSource random = ctx.random();
        BlockPos origin = ctx.origin();
        WorldGenLevel level = ctx.level();
        SectionCursor cursor = SectionCursor.mine(SectionCursor.GEODE, region);
        FeatureStats.fast(GeodeFeature.class);
        int minOffset = config.minGenOffset;
        int maxOffset = config.maxGenOffset;
        int points = config.distributionPoints.sample(random);
        Last lastNoise = noise(level.getSeed());
        NormalNoise normalNoise = lastNoise.noise;
        double d0 = (double) points / (double) config.outerWallDistance.getMaxValue();
        GeodeLayerSettings layers = config.geodeLayerSettings;
        GeodeBlockSettings blocks = config.geodeBlockSettings;
        GeodeCrackSettings cracks = config.geodeCrackSettings;
        double d1 = 1.0 / Math.sqrt(layers.filling);
        double d2 = 1.0 / Math.sqrt(layers.innerLayer + d0);
        double d3 = 1.0 / Math.sqrt(layers.middleLayer + d0);
        double d4 = 1.0 / Math.sqrt(layers.outerLayer + d0);
        double d5 = 1.0 / Math.sqrt(cracks.baseCrackSize + random.nextDouble() / 2.0 + (points > 3 ? d0 : 0.0));
        boolean crack = (double) random.nextFloat() < cracks.generateCrackChance;
        int invalid = 0;
        BlockPos[] pointPos = new BlockPos[points];
        int[] pointOffset = new int[points];
        for (int i = 0; i < points; i++) {
            int dx = config.outerWallDistance.sample(random);
            int dy = config.outerWallDistance.sample(random);
            int dz = config.outerWallDistance.sample(random);
            BlockPos p = origin.offset(dx, dy, dz);
            BlockState state = cursor.read(p);
            if (state.isAir() || state.is(BlockTags.GEODE_INVALID_BLOCKS)) {
                if (++invalid > config.invalidBlocksThreshold) {
                    return false;
                }
            }
            pointPos[i] = p;
            pointOffset[i] = config.pointOffset.sample(random);
        }
        BlockPos[] crackPos = new BlockPos[0];
        if (crack) {
            int side = random.nextInt(4);
            int d = points * 2 + 1;
            crackPos = switch (side) {
                case 0 -> new BlockPos[] { origin.offset(d, 7, 0), origin.offset(d, 5, 0), origin.offset(d, 1, 0) };
                case 1 -> new BlockPos[] { origin.offset(0, 7, d), origin.offset(0, 5, d), origin.offset(0, 1, d) };
                case 2 -> new BlockPos[] { origin.offset(d, 7, d), origin.offset(d, 5, d), origin.offset(d, 1, d) };
                default -> new BlockPos[] { origin.offset(0, 7, 0), origin.offset(0, 5, 0), origin.offset(0, 1, 0) };
            };
        }
        List<BlockPos> placements = new ArrayList<>();
        Predicate<BlockState> predicate = Feature.isReplaceable(blocks.cannotReplace);
        double crackOffset = (double) cracks.crackPointOffset;
        // |noise| never exceeds the noise's maxValue. Each octave's value lies in [-2, 2], and maxValue sums those bounds.
        // Take a block whose distance terms stay under the outer layer even with every point's term raised by that bound.
        // That block has d6 < d4 whatever the noise. Vanilla does nothing there (no draw, no write), and the noise is not
        // asked. The margin covers the rounding. maxValue sums the amplitudes as they are, so it is a bound only when none
        // is negative.
        double slack = nonNegativeAmplitudes(normalNoise)
            ? (double) points * Math.abs((double) config.noiseMultiplier) * normalNoise.maxValue() * 1.000001
            : Double.POSITIVE_INFINITY;
        // BlockPos.betweenClosed's order (x, then y, then z) over the same box, on a mutable cursor per z slab
        int ax = origin.getX() + minOffset, bx = origin.getX() + maxOffset;
        int ay = origin.getY() + minOffset, by = origin.getY() + maxOffset;
        int az = origin.getZ() + minOffset, bz = origin.getZ() + maxOffset;
        int x0 = Math.min(ax, bx), x1 = Math.max(ax, bx), y0 = Math.min(ay, by), y1 = Math.max(ay, by), z0 = Math.min(az, bz), z1 = Math.max(az, bz);
        int nx = x1 - x0 + 1;
        // with vanilla's settings the bound above never holds (its slack exceeds the outer layer), so the noise is asked at every block
        // of the box. A z slab's values are made column by column, on first need. The octaves' lattice terms are kept down a column.
        Scratch scratch = Scratch.of(lastNoise.spec, nx * (y1 - y0 + 1), points);
        double[] slab = scratch.slab;
        double[] inv = scratch.inv;
        NoiseSpec.Column column = scratch.column;
        for (int z = z0; z <= z1; z++) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            boolean slabMade = false;
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    pos.set(x, y, z);
                    double base = 0.0;
                    for (int i = 0; i < points; i++) {
                        double v = Mth.invSqrt(pos.distSqr(pointPos[i]) + (double) pointOffset[i]);
                        inv[i] = v;
                        base += v;
                    }
                    if (base + slack + 1.0E-9 * (Math.abs(base) + slack + 1.0) < d4) {
                        continue;
                    }
                    if (!slabMade) {
                        for (int cx = x0; cx <= x1; cx++) {
                            column.prepare((double) cx, (double) z);
                            for (int cy = y0; cy <= y1; cy++) {
                                slab[(cy - y0) * nx + (cx - x0)] = column.value((double) cy);
                            }
                        }
                        slabMade = true;
                    }
                    // NormalNoise.getValue at the block, as the spec's column computes it. Each point's term is the bound's term.
                    double d8 = slab[(y - y0) * nx + (x - x0)] * config.noiseMultiplier;
                    double d6 = 0.0;
                    double d7 = 0.0;
                    for (int i = 0; i < points; i++) {
                        d6 += inv[i] + d8;
                    }
                    for (BlockPos c : crackPos) {
                        d7 += Mth.invSqrt(pos.distSqr(c) + crackOffset) + d8;
                    }
                    if (!(d6 < d4)) {
                        if (crack && d7 >= d5 && d6 < d1) {
                            Fast.safeSetBlock(cursor, pos, Blocks.AIR.defaultBlockState(), predicate);
                            for (Direction direction : DIRECTIONS) {
                                BlockPos neighbour = pos.relative(direction);
                                FluidState fluid = cursor.readFluid(neighbour);
                                if (!fluid.isEmpty()) {
                                    level.scheduleTick(neighbour, fluid.getType(), 0);
                                }
                            }
                        } else if (d6 >= d1) {
                            Fast.safeSetBlock(cursor, pos, blocks.fillingProvider.getState(random, pos), predicate);
                        } else if (d6 >= d2) {
                            boolean alternate = (double) random.nextFloat() < config.useAlternateLayer0Chance;
                            if (alternate) {
                                Fast.safeSetBlock(cursor, pos, blocks.alternateInnerLayerProvider.getState(random, pos), predicate);
                            } else {
                                Fast.safeSetBlock(cursor, pos, blocks.innerLayerProvider.getState(random, pos), predicate);
                            }
                            if ((!config.placementsRequireLayer0Alternate || alternate) && (double) random.nextFloat() < config.usePotentialPlacementsChance) {
                                placements.add(pos.immutable());
                            }
                        } else if (d6 >= d3) {
                            Fast.safeSetBlock(cursor, pos, blocks.middleLayerProvider.getState(random, pos), predicate);
                        } else if (d6 >= d4) {
                            Fast.safeSetBlock(cursor, pos, blocks.outerLayerProvider.getState(random, pos), predicate);
                        }
                    }
                }
            }
        }
        List<BlockState> inner = blocks.innerPlacements;
        for (BlockPos pos : placements) {
            BlockState state = net.minecraft.Util.getRandom(inner, random);
            for (Direction direction : DIRECTIONS) {
                if (state.hasProperty(BlockStateProperties.FACING)) {
                    state = state.setValue(BlockStateProperties.FACING, direction);
                }
                BlockPos neighbour = pos.relative(direction);
                BlockState neighbourState = cursor.read(neighbour);
                if (state.hasProperty(BlockStateProperties.WATERLOGGED)) {
                    state = state.setValue(BlockStateProperties.WATERLOGGED, Boolean.valueOf(neighbourState.getFluidState().isSource()));
                }
                if (BuddingAmethystBlock.canClusterGrowAtState(neighbourState)) {
                    Fast.safeSetBlock(cursor, neighbour, state, predicate);
                    break;
                }
            }
        }
        // other mods' handlers at the method's tail (before its last return)
        FeatureWraps.geodeDone(feature, ctx);
        return true;
    }
}
