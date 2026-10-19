package dev.denismasterherobrine.generatoraccelerator2.features.placement;

import dev.denismasterherobrine.generatoraccelerator2.features.FeatureStats;
import dev.denismasterherobrine.generatoraccelerator2.features.FeaturesCompat;
import dev.denismasterherobrine.generatoraccelerator2.features.region.Ga2Region;
import dev.denismasterherobrine.generatoraccelerator2.features.region.RegionReads;
import dev.denismasterherobrine.generatoraccelerator2.mixin.features.PlacementFilterInvoker;
import dev.denismasterherobrine.generatoraccelerator2.mixin.features.RepeatingPlacementInvoker;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.BlockPredicateFilter;
import net.minecraft.world.level.levelgen.placement.CountOnEveryLayerPlacement;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.HeightmapPlacement;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.NoiseBasedCountPlacement;
import net.minecraft.world.level.levelgen.placement.NoiseThresholdCountPlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RandomOffsetPlacement;
import net.minecraft.world.level.levelgen.placement.RarityFilter;
import net.minecraft.world.level.levelgen.placement.RepeatingPlacement;
import net.minecraft.world.level.levelgen.placement.SurfaceRelativeThresholdFilter;
import net.minecraft.world.level.levelgen.placement.SurfaceWaterDepthFilter;

/**
 * {@code PlacedFeature.placeWithContext} without the stream chain. Vanilla pushes every position
 * depth-first through the remaining modifiers and the feature before the previous modifier produces
 * its next position (JDK {@code flatMap} semantics). That is exactly a recursion. Vanilla modifier
 * classes are evaluated by transcriptions of their {@code getPositions} (same random calls, same
 * {@code BlockPos} objects where identity could matter). Any other filter is asked {@code shouldPlace}. A filter here is
 * a {@code PlacementFilter}, whose {@code getPositions} vanilla makes final. Any other {@code RepeatingPlacement} that
 * keeps vanilla's {@code getPositions} is asked {@code count} ({@link GenericModifiers}). Any other class runs its own
 * {@code getPositions}, and the returned stream is consumed in order. So modded modifiers keep their exact behaviour.
 */
public final class PlacementPipeline {
    private PlacementPipeline() {}

    /**
     * Another mod's wrapper of the {@code forEach} that places a placed feature at each of its positions. It is asked at each
     * position. {@code compat/<modid>} sets it before worldgen. For a handled mixin whose wrapper is not set, the pipeline
     * stays off. {@code place} is vanilla's consumer: it places the feature there and sets {@code success} when it does.
     */
    @FunctionalInterface
    public interface PositionWrap {
        void place(PlacedFeature placed, BlockPos pos, java.util.function.Consumer<BlockPos> place, PlacementContext ctx, RandomSource random,
                   org.apache.commons.lang3.mutable.MutableBoolean success);
    }

    public static volatile PositionWrap position;

    /** {@code -Dgeneratoraccelerator2.biomeSet=false}: every biome check looks the biome up. */
    private static final boolean BIOME_SET = !"false".equals(System.getProperty("generatoraccelerator2.biomeSet"));
    /** {@code -Dgeneratoraccelerator2.verify.biomeSet=N}: every N-th check the biome set answers is looked up after all. */
    private static final dev.denismasterherobrine.generatoraccelerator2.chunkio.VerifyCounter VERIFY_BIOME_SET =
        new dev.denismasterherobrine.generatoraccelerator2.chunkio.VerifyCounter("biomeSet");

    /** Per modifier class: true for a vanilla class the pipeline transcribes that no other mod changes ({@code placement.<class>}). */
    private static final ClassValue<Boolean> TRANSCRIBED = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return type.getName().startsWith("net.minecraft.") && dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets.allowed(
                dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets.modifierKey(type.getName().replace('.', '/')));
        }
    };

    /** What {@link Run#walk} does at a modifier: one of the transcriptions, or the modifier's own methods ({@code OTHER}). */
    private static final byte OTHER = 0, COUNT = 1, IN_SQUARE = 2, HEIGHTMAP = 3, HEIGHT_RANGE = 4, BIOME = 5, RARITY = 6, PREDICATE = 7,
        WATER_DEPTH = 8, SURFACE_RELATIVE = 9, NOISE_THRESHOLD = 10, NOISE_COUNT = 11, RANDOM_OFFSET = 12;

    private static byte kind(PlacementModifier m) {
        Class<?> cls = m.getClass();
        // a vanilla class another mod changes goes through its own methods (the generic branches of walkOther)
        if (!TRANSCRIBED.get(cls)) {
            return OTHER;
        }
        if (cls == CountPlacement.class) {
            return COUNT;
        } else if (cls == InSquarePlacement.class) {
            return IN_SQUARE;
        } else if (cls == HeightmapPlacement.class) {
            return HEIGHTMAP;
        } else if (cls == HeightRangePlacement.class) {
            return HEIGHT_RANGE;
        } else if (cls == BiomeFilter.class) {
            return BIOME;
        } else if (cls == RarityFilter.class) {
            return RARITY;
        } else if (cls == BlockPredicateFilter.class) {
            return PREDICATE;
        } else if (cls == SurfaceWaterDepthFilter.class) {
            return WATER_DEPTH;
        } else if (cls == SurfaceRelativeThresholdFilter.class) {
            return SURFACE_RELATIVE;
        } else if (cls == NoiseThresholdCountPlacement.class) {
            return NOISE_THRESHOLD;
        } else if (cls == NoiseBasedCountPlacement.class) {
            return NOISE_COUNT;
        } else if (cls == RandomOffsetPlacement.class) {
            return RANDOM_OFFSET;
        }
        return OTHER;
    }

    /** {@code -Dgeneratoraccelerator2.oreTiming=true}: the time ore features take in decoration (placed or committed), for comparisons. */
    private static final boolean ORE_TIMING = Boolean.getBoolean("generatoraccelerator2.oreTiming");
    public static final java.util.concurrent.atomic.LongAdder ORE_NS = new java.util.concurrent.atomic.LongAdder();

    public static boolean place(PlacedFeature placed, PlacementContext ctx, RandomSource random, BlockPos origin) {
        // the GPU may have placed it already (gpu.decoration). The GPU's result is written when nothing it read changed since.
        dev.denismasterherobrine.generatoraccelerator2.gpu.decor.DecorJob job = dev.denismasterherobrine.generatoraccelerator2.gpu.decor.DecorJob.CURRENT.get();
        boolean ore = ORE_TIMING && placed.feature().value().feature() == net.minecraft.world.level.levelgen.feature.Feature.ORE;
        long t0 = ore ? System.nanoTime() : 0L;
        try {
            if (job != null) {
                Boolean gpu = job.place(placed, ctx, random, origin, () -> placeCpu(placed, ctx, random, origin));
                if (gpu != null) {
                    return gpu;
                }
                // what the CPU's placement costs, against what the GPU's results cost (features no cheaper there leave the jobs)
                long tc = System.nanoTime();
                try {
                    return placeCpu(placed, ctx, random, origin);
                } finally {
                    job.placedOnCpu(placed, ctx, System.nanoTime() - tc);
                }
            }
            return placeCpu(placed, ctx, random, origin);
        } finally {
            if (ore) {
                ORE_NS.add(System.nanoTime() - t0);
            }
        }
    }

    /**
     * Each thread's runs, one per nesting depth. A feature may place other placed features while its own placement runs. A run
     * is {@link Run#start}ed per placement instead of made anew.
     */
    private static final class Runs {
        Run[] runs = new Run[4];
        int depth;

        Run take() {
            if (depth == runs.length) {
                runs = java.util.Arrays.copyOf(runs, depth * 2);
            }
            Run run = runs[depth];
            if (run == null) {
                run = runs[depth] = new Run();
            }
            depth++;
            return run;
        }
    }

    private static final ThreadLocal<Runs> RUNS = ThreadLocal.withInitial(Runs::new);

    /** The pipeline itself. */
    public static boolean placeCpu(PlacedFeature placed, PlacementContext ctx, RandomSource random, BlockPos origin) {
        Runs runs = RUNS.get();
        Run run = runs.take();
        try {
            run.start(placed, placed.placement(), placed.feature().value(), ctx, random, position);
            run.walk(0, origin);
            FeatureStats.PLACEMENTS_FAST.increment();
            return run.placed || run.success != null && run.success.isTrue();
        } finally {
            run.end();
            runs.depth--;
        }
    }

    private static final class Run {
        private PlacedFeature placedFeature;
        private PositionWrap wrap;
        /** Vanilla's {@code MutableBoolean} of the placement, when a wrapper takes part ({@link PositionWrap}). */
        org.apache.commons.lang3.mutable.MutableBoolean success;
        private java.util.function.Consumer<BlockPos> consumer;
        private List<PlacementModifier> modifiers;
        /** {@link #kind} of each modifier, found once per placement (the walk visits each many times). */
        private byte[] kinds = new byte[8];
        private int count;
        private ConfiguredFeature<?, ?> feature;
        private PlacementContext ctx;
        private RandomSource random;
        boolean placed;
        // hasFeature memo for the biome filter: biomes seen during this placement, by holder identity
        private Holder<Biome> memoBiome0;
        private Holder<Biome> memoBiome1;
        private boolean memoResult0;
        private boolean memoResult1;

        /** As a new run of this placement. */
        void start(PlacedFeature placed, List<PlacementModifier> modifiers, ConfiguredFeature<?, ?> feature, PlacementContext ctx, RandomSource random, PositionWrap wrap) {
            this.placedFeature = placed;
            this.wrap = wrap;
            this.modifiers = modifiers;
            int n = this.count = modifiers.size();
            if (kinds.length < n) {
                kinds = new byte[n];
            }
            for (int k = 0; k < n; k++) {
                kinds[k] = kind(modifiers.get(k));
            }
            this.feature = feature;
            this.ctx = ctx;
            this.random = random;
            this.success = null;
            this.consumer = null;
            this.placed = false;
            this.memoBiome0 = null;
            this.memoBiome1 = null;
            this.memoResult0 = false;
            this.memoResult1 = false;
            this.allBiomesHave = 0;
            this.zoom = null;
        }

        /** Lets go of the placement's objects (the run stays with the thread). */
        void end() {
            this.placedFeature = null;
            this.wrap = null;
            this.success = null;
            this.consumer = null;
            this.modifiers = null;
            this.feature = null;
            this.ctx = null;
            this.random = null;
            this.memoBiome0 = null;
            this.memoBiome1 = null;
            this.zoom = null;
        }

        /** The last step with another mod's wrapper: vanilla's consumer for this placement, handed to the wrapper at each position. */
        private void placeWrapped(BlockPos pos) {
            if (consumer == null) {
                success = new org.apache.commons.lang3.mutable.MutableBoolean();
                consumer = p -> {
                    if (feature.place(ctx.getLevel(), ctx.generator(), random, p)) {
                        success.setTrue();
                    }
                };
            }
            wrap.place(placedFeature, pos, consumer, ctx, random, success);
        }

        void walk(int i, BlockPos pos) {
            walk(i, pos.getX(), pos.getY(), pos.getZ(), pos);
        }

        /**
         * The placement from modifier {@code i} on, at {@code x, y, z}. {@code pos} is that position when one was made, else null. A
         * made position is the origin, or a position a modifier gave or the walk had to make. The transcribed modifiers read the
         * coordinates. A position is made only for what takes one (a predicate, another mod's modifier, the feature). It is made
         * once for every walk that goes on from it.
         */
        void walk(int i, int x, int y, int z, BlockPos pos) {
            if (i == count) {
                if (pos == null) {
                    pos = new BlockPos(x, y, z);
                }
                if (wrap != null) {
                    placeWrapped(pos);
                } else if (feature.place(ctx.getLevel(), ctx.generator(), random, pos)) {
                    placed = true;
                }
                return;
            }
            PlacementModifier m = modifiers.get(i);
            byte kind = kinds[i];
            if (kind == COUNT) {
                int n = ((CountPlacement) m).count.sample(random);
                if (n > 1 && pos == null) {
                    pos = new BlockPos(x, y, z);
                }
                for (int k = 0; k < n; k++) {
                    walk(i + 1, x, y, z, pos);
                }
            } else if (kind == IN_SQUARE) {
                int nx = random.nextInt(16) + x;
                int nz = random.nextInt(16) + z;
                walk(i + 1, nx, y, nz, null);
            } else if (kind == HEIGHTMAP) {
                int ny = ctx.getHeight(((HeightmapPlacement) m).heightmap, x, z);
                if (ny > ctx.getMinBuildHeight()) {
                    walk(i + 1, x, ny, z, null);
                }
            } else if (kind == HEIGHT_RANGE) {
                // pos.atY: the same position at the same height
                int ny = ((HeightRangePlacement) m).height.sample(random, ctx);
                walk(i + 1, x, ny, z, ny == y ? pos : null);
            } else if (kind == BIOME) {
                if (biomeHasFeature(x, y, z)) {
                    walk(i + 1, x, y, z, pos);
                }
            } else if (kind == RARITY) {
                if (random.nextFloat() < 1.0F / (float) ((RarityFilter) m).chance) {
                    walk(i + 1, x, y, z, pos);
                }
            } else if (kind == PREDICATE) {
                if (pos == null) {
                    pos = new BlockPos(x, y, z);
                }
                // the filter keeps its predicate's program (BlockPredicateFilterPredicateMixin)
                if (((dev.denismasterherobrine.generatoraccelerator2.features.predicate.PredicatePrograms.FilterProgram) (Object) m).ga2$test(ctx.getLevel(), pos)) {
                    walk(i + 1, x, y, z, pos);
                }
            } else if (kind == WATER_DEPTH) {
                int floor = ctx.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
                int surface = ctx.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                if (surface - floor <= ((SurfaceWaterDepthFilter) m).maxWaterDepth) {
                    walk(i + 1, x, y, z, pos);
                }
            } else if (kind == SURFACE_RELATIVE) {
                SurfaceRelativeThresholdFilter f = (SurfaceRelativeThresholdFilter) m;
                long h = (long) ctx.getHeight(f.heightmap, x, z);
                long lo = h + (long) f.minInclusive;
                long hi = h + (long) f.maxInclusive;
                if (lo <= (long) y && (long) y <= hi) {
                    walk(i + 1, x, y, z, pos);
                }
            } else if (kind == NOISE_THRESHOLD) {
                NoiseThresholdCountPlacement p = (NoiseThresholdCountPlacement) m;
                double d = Biome.BIOME_INFO_NOISE.getValue((double) x / 200.0, (double) z / 200.0, false);
                int n = d < p.noiseLevel ? p.belowNoise : p.aboveNoise;
                if (n > 1 && pos == null) {
                    pos = new BlockPos(x, y, z);
                }
                for (int k = 0; k < n; k++) {
                    walk(i + 1, x, y, z, pos);
                }
            } else if (kind == NOISE_COUNT) {
                NoiseBasedCountPlacement p = (NoiseBasedCountPlacement) m;
                double d = Biome.BIOME_INFO_NOISE.getValue((double) x / p.noiseFactor, (double) z / p.noiseFactor, false);
                int n = (int) Math.ceil((d + p.noiseOffset) * (double) p.noiseToCountRatio);
                if (n > 1 && pos == null) {
                    pos = new BlockPos(x, y, z);
                }
                for (int k = 0; k < n; k++) {
                    walk(i + 1, x, y, z, pos);
                }
            } else if (kind == RANDOM_OFFSET) {
                RandomOffsetPlacement p = (RandomOffsetPlacement) m;
                int nx = x + p.xzSpread.sample(random);
                int ny = y + p.ySpread.sample(random);
                int nz = z + p.xzSpread.sample(random);
                walk(i + 1, nx, ny, nz, null);
            } else {
                if (pos == null) {
                    pos = new BlockPos(x, y, z);
                }
                walkOther(i, m, m.getClass(), pos);
            }
        }

        /** {@link #walk} through a modifier that takes a position: the layer search, and every modifier not transcribed. */
        private void walkOther(int i, PlacementModifier m, Class<?> cls, BlockPos pos) {
            RegionReads reads;
            if (cls == CountOnEveryLayerPlacement.class && (reads = layerReads()) != null) {
                List<BlockPos> found = EveryLayer.positions(((CountOnEveryLayerPlacement) m).count, ctx, random, pos, reads);
                for (int k = 0, n = found.size(); k < n; k++) {
                    walk(i + 1, found.get(k));
                }
            } else if (m instanceof PlacementFilter && GenericModifiers.filters()) {
                // any filter (vanilla's untranscribed ones, any mod's): getPositions is the final shouldPlace ? pos : nothing
                FeatureStats.PLACEMENTS_GENERIC_FILTERS.increment();
                if (((PlacementFilterInvoker) m).ga2$shouldPlace(ctx, random, pos)) {
                    walk(i + 1, pos);
                }
            } else if (m instanceof RepeatingPlacement && GenericModifiers.repeats(cls)) {
                // vanilla's getPositions repeats the same position count(...) times
                FeatureStats.PLACEMENTS_GENERIC_REPEATS.increment();
                int n = ((RepeatingPlacementInvoker) m).ga2$count(random, pos);
                for (int k = 0; k < n; k++) {
                    walk(i + 1, pos);
                }
            } else {
                FeatureStats.PLACEMENTS_GENERIC_MODIFIERS.increment();
                FeatureStats.genericModifier(cls);
                try (Stream<BlockPos> positions = m.getPositions(ctx, random, pos)) {
                    if (positions != null) {
                        positions.sequential().forEach(p -> walk(i + 1, p));
                    }
                }
            }
        }

        /** The region's in-place reads when the layer search may use them ({@link EveryLayer}), else null (vanilla's own search). */
        private RegionReads layerReads() {
            if (!FeaturesCompat.placementLayersAllowed() || !(ctx.getLevel() instanceof Ga2Region region)) {
                return null;
            }
            return region.ga2$reads();
        }

        /** 0 not known yet, 1 every biome of the region has the top feature, 2 not (or no zoom). */
        private int allBiomesHave;
        private dev.denismasterherobrine.generatoraccelerator2.surface.BiomeZoom zoom;

        private boolean allRegionBiomesHave(PlacedFeature top) {
            if (!BIOME_SET || !(ctx.getLevel() instanceof Ga2Region region)) {
                return false;
            }
            zoom = region.ga2$zoom();
            Holder<Biome>[] biomes = zoom == null ? null : region.ga2$zoomBiomes();
            if (biomes == null || biomes.length == 0) {
                return false;
            }
            for (Holder<Biome> biome : biomes) {
                if (!ctx.generator().getBiomeGenerationSettings(biome).hasFeature(top)) {
                    return false;
                }
            }
            return true;
        }

        /** Where {@link #biomeHasFeature} asks the level for a biome (a lookup reads the position's coordinates only). */
        private final BlockPos.MutableBlockPos biomePos = new BlockPos.MutableBlockPos();

        /** Exact {@code BiomeFilter.shouldPlace}, with the biome's answer remembered by holder identity for this placement. */
        private boolean biomeHasFeature(int x, int y, int z) {
            PlacedFeature top = ctx.topFeature()
                .orElseThrow(() -> new IllegalStateException("Tried to biome check an unregistered feature, or a feature that should not restrict the biome"));
            if (allBiomesHave == 0) {
                allBiomesHave = allRegionBiomesHave(top) ? 1 : 2;
            }
            BlockPos pos = biomePos.set(x, y, z);
            if (allBiomesHave == 1 && zoom.owner() == ctx.getLevel() && zoom.covers(x, y, z)) {
                // every biome the lookup could answer has the feature
                if (FeatureStats.DETAILED) {
                    FeatureStats.BIOME_SET_HITS.increment();
                }
                if (VERIFY_BIOME_SET.sample()) {
                    Holder<Biome> real = ctx.getLevel().getBiome(pos);
                    VERIFY_BIOME_SET.result(ctx.generator().getBiomeGenerationSettings(real).hasFeature(top), pos.immutable(), "true for " + real.getRegisteredName(), false);
                }
                return true;
            }
            Holder<Biome> biome = ctx.getLevel().getBiome(pos);
            if (biome == memoBiome0) {
                if (FeatureStats.DETAILED) {
                    FeatureStats.BIOME_MEMO_HITS.increment();
                }
                return memoResult0;
            }
            if (biome == memoBiome1) {
                if (FeatureStats.DETAILED) {
                    FeatureStats.BIOME_MEMO_HITS.increment();
                }
                return memoResult1;
            }
            boolean result = ctx.generator().getBiomeGenerationSettings(biome).hasFeature(top);
            memoBiome1 = memoBiome0;
            memoResult1 = memoResult0;
            memoBiome0 = biome;
            memoResult0 = result;
            return result;
        }
    }
}
