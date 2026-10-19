package dev.denismasterherobrine.generatoraccelerator2.gpu.decor;

import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.features.FeaturesCompat;
import dev.denismasterherobrine.generatoraccelerator2.features.predicate.PredicatePrograms;
import dev.denismasterherobrine.generatoraccelerator2.features.region.TagEpoch;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.BiasedToBottomHeightAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.ChunkGeneratorFeaturesAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.ClampedIntAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.InsideWorldBoundsPredicateAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.RotatedBlockProviderAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.SimpleStateProviderAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.TrapezoidHeightAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.UniformHeightAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.VeryBiasedToBottomHeightAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.WeightedListIntAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.WeightedStateProviderAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.WouldSurvivePredicateAccessor;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.WeightedEntry;
import net.minecraft.util.valueproviders.BiasedToBottomInt;
import net.minecraft.util.valueproviders.ClampedInt;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.util.valueproviders.WeightedListInt;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.blockpredicates.StateTestingPredicate;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.RandomBooleanSelectorFeature;
import net.minecraft.world.level.levelgen.feature.RandomPatchFeature;
import net.minecraft.world.level.levelgen.feature.RandomSelectorFeature;
import net.minecraft.world.level.levelgen.feature.SimpleBlockFeature;
import net.minecraft.world.level.levelgen.feature.SimpleRandomSelectorFeature;
import net.minecraft.world.level.levelgen.feature.SpringFeature;
import net.minecraft.world.level.levelgen.feature.WeightedPlacedFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.RandomBooleanFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.RandomFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.RandomPatchConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleBlockConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleRandomFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SpringConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.feature.stateproviders.RotatedBlockProvider;
import net.minecraft.world.level.levelgen.feature.stateproviders.SimpleStateProvider;
import net.minecraft.world.level.levelgen.feature.stateproviders.WeightedStateProvider;
import net.minecraft.world.level.levelgen.heightproviders.BiasedToBottomHeight;
import net.minecraft.world.level.levelgen.heightproviders.ConstantHeight;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.heightproviders.TrapezoidHeight;
import net.minecraft.world.level.levelgen.heightproviders.UniformHeight;
import net.minecraft.world.level.levelgen.heightproviders.VeryBiasedToBottomHeight;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.BlockPredicateFilter;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.EnvironmentScanPlacement;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.HeightmapPlacement;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.NoiseBasedCountPlacement;
import net.minecraft.world.level.levelgen.placement.NoiseThresholdCountPlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RandomOffsetPlacement;
import net.minecraft.world.level.levelgen.placement.RarityFilter;
import net.minecraft.world.level.levelgen.placement.SurfaceRelativeThresholdFilter;
import net.minecraft.world.level.levelgen.placement.SurfaceWaterDepthFilter;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockStateMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest;
import net.minecraft.world.level.material.Fluid;

/**
 * The placed features of a dimension that the GPU places as vanilla does ({@code gpu.decoration}), as data the GPU kernel interprets
 * ({@link DecorKernel}). Every placed feature the GPU can place, top-level or nested in a patch or selector, is an entry of
 * {@link #fspec}. An entry holds the configured feature's kind and parameters and its placement modifiers. Everything a GPU feature
 * reads is data the GPU has (the 3x3 chunks' blocks, heightmaps, stored biomes, the random draws). Every question a GPU feature asks
 * of a block is a {@link Preds} predicate of the block's state. So the CPU can ask it again of a block that changed since.
 *
 * <p>The parts of vanilla's own objects that the GPU emulates (int, height and block state providers) are first compared with those
 * objects. Each provider is sampled both ways over many seeds (values, draw counts and the random's state after). Any difference keeps
 * the feature on the CPU. A rule a block's survival follows is learnt by running it ({@link SurvivalProbe}).
 *
 * <p>Layout. {@link #fspec} starts with each entry's offset. An entry is {@link #HEAD} ints (kind, modifier count, flags, parameter
 * count), then its modifiers (4 ints each: op, a, b, c), then its kind's parameters. Providers, predicate programs and the modifiers'
 * larger arguments are in {@link #aux}. The ore kind's sine tables are in {@link #fsin}.
 */
public final class DecorSpec {
    // capacities shared with the kernel
    public static final int MAX_SIZE = 64, MAX_TARGETS = 8, MAX_OPS = 8;
    /** Bitset entries of {@code OreFeature.doPlace}'s index space (the largest size's, with room for its overshoot). */
    public static final int L2 = 10240;
    /** Box cells of one ore placement's spheres (64 spheres of at most 10^3). */
    public static final int CAND = 65536;
    public static final int MAXF = 96;
    /** Threads per job. */
    public static final int WG = 256;
    /** Effects and block reads a job may record. */
    public static final int MAXW = 16384, MAXR = 65536;
    /** Composite features (patches, selectors) nested below a top-level placed feature. */
    public static final int MAX_NEST = 2;
    static final int HEAD = 8;

    /** Configured feature kinds. */
    public static final int K_ORE = 1, K_SIMPLE = 2, K_PATCH = 3, K_SELECT = 4, K_SSELECT = 5, K_BSELECT = 6, K_SPRING = 7, K_TREE = 8,
        K_SEAGRASS = 9, K_KELP = 10, K_PICKLE = 11, K_COLUMN = 12, K_DISK = 13, K_MULTIFACE = 14, K_VINES = 15, K_BLOB = 16, K_SNOW = 17, K_MUSHROOM = 18;
    static final String[] KIND_NAMES = { "?", "ore", "simple_block", "random_patch", "random_selector", "simple_random_selector",
        "random_boolean_selector", "spring", "tree", "seagrass", "kelp", "sea_pickle", "block_column", "disk", "multiface_growth", "vines", "block_blob", "snow_and_freeze", "huge_mushroom" };

    /** Placement modifier ops (op, a, b, c). */
    static final int OP_COUNT = 1, OP_SQUARE = 2, OP_HEIGHT = 3, OP_BIOME = 4, OP_RARITY = 5, OP_HEIGHTMAP = 6, OP_FILTER = 7, OP_OFFSET = 8,
        OP_WATER_DEPTH = 9, OP_SURFACE_REL = 10, OP_NOISE_THRESHOLD = 11, OP_NOISE_COUNT = 12, OP_ENV_SCAN = 13;
    /** Int providers in {@link #aux}. */
    static final int IP_CONST = 1, IP_UNIFORM = 2, IP_BIASED = 3, IP_CLAMPED = 4, IP_WEIGHTED = 5;
    /** Height providers in {@link #aux} (anchors resolved). */
    static final int HP_CONST = 1, HP_UNIFORM = 2, HP_TRAPEZOID = 3, HP_BIASED = 4, HP_VERY_BIASED = 5;
    /** Block predicate programs in {@link #aux}: length, then postfix ops. */
    static final int PR_TRUE = 1, PR_FALSE = 2, PR_STATE = 3, PR_BOUNDS = 4, PR_NOT = 5, PR_ALL = 6, PR_ANY = 7;
    /** A plant surviving by a rule over several blocks ({@link #SV_MULTI}): (PR_SURVIVE, dx, dy, dz, rule). */
    static final int PR_SURVIVE = 8;
    /** Block state providers in {@link #aux}. Each state is a plant record (state, survival mode, survival predicate, upper half or -1). */
    static final int SP_SIMPLE = 1, SP_WEIGHTED = 2, SP_ROTATED = 3;
    /**
     * A randomized-int provider (cave vines' age): (SP_RANDINT, source provider, int provider, lowest value, value count), then per source
     * record (whether its state has the property, then the record per value).
     */
    static final int SP_RANDINT = 4;
    /** Record kinds (4 ints each): a plant (state, survival, its predicate, upper half), a plain state, a log (state, then with axis x, y, z), a leaf (dry, waterlogged, whether it has the property). */
    static final int REC_PLANT = 0, REC_PLAIN = 1, REC_AXIS = 2, REC_LEAF = 3;
    /**
     * Survival modes: always, by a predicate of the block below, never, or by a rule over several blocks. The rule is a
     * {@link SurvivalProbe.Multi} in {@link #aux}: count, then (dx, dy, dz, class table, stride) per block, then the answer bits.
     */
    static final int SV_ALWAYS = 0, SV_BELOW = 1, SV_NEVER = 2, SV_MULTI = 3;
    /** Heightmaps as the kernel numbers them: the chunks' two worldgen maps (fixed from SURFACE on), then {@link Preds#FINAL}. */
    static final int HM_WS_WG = 0, HM_OF_WG = 1;

    /**
     * Effects (2 ints: where, op << 28 | flags << 16 | id). A tree ends with its box (EF_TREE, two entries), its shape's cells (EF_SHAPE) and
     * EF_EDGE, where the CPU runs {@code StructureTemplate.updateShapeAtEdge}. EF_EDGE_SET entries are that pass's writes as the GPU foresaw
     * them. They are not replayed, since the pass writes them.
     */
    public static final int EF_SECTION = 0, EF_SET = 1, EF_TICK_FLUID = 2, EF_TICK_BLOCK = 3, EF_TREE = 4, EF_SHAPE = 5, EF_EDGE = 6, EF_EDGE_SET = 7, EF_POST = 8;
    /**
     * Reads of the generic kinds (2 ints: where | type << 24, aux). RT_STATE: the exact state id (aux). RT_VALUE: a value table's
     * value (aux = table << 20 | value).
     */
    public static final int RT_PRED = 1, RT_HEIGHT = 2, RT_STATE = 3, RT_VALUE = 4;
    /** A block whose block light the GPU took to be below 10, since no light exists before the LIGHT step. The CPU asks the level. */
    public static final int RT_LIGHT = 5;
    /** Result flags per feature: bit 0 placed, bit 1 left to the CPU, bit 2 the job ran out of room (every later feature too). */
    public static final int R_PLACED = 1, R_CPU = 2, R_FULL = 4;

    private static final RandomSource NO_RANDOM = RandomSource.create(0L);
    private static final String PRED_PKG = "net.minecraft.world.level.levelgen.blockpredicates.";

    // ---- what the GPU places

    /** The top-level placed features the GPU places. A job lists them by their index here. */
    public final List<PlacedFeature> features = new ArrayList<>();
    /**
     * Per feature: results compared so far (probation), and the difference that took the feature off the GPU. Kept here because it is
     * read at every use.
     */
    private volatile java.util.concurrent.atomic.AtomicIntegerArray tried;
    private volatile java.util.concurrent.atomic.AtomicReferenceArray<String> off;

    java.util.concurrent.atomic.AtomicIntegerArray tried() {
        var t = tried;
        if (t == null) {
            synchronized (this) {
                if ((t = tried) == null) {
                    tried = t = new java.util.concurrent.atomic.AtomicIntegerArray(features.size());
                }
            }
        }
        return t;
    }

    java.util.concurrent.atomic.AtomicReferenceArray<String> off() {
        var o = off;
        if (o == null) {
            synchronized (this) {
                if ((o = off) == null) {
                    off = o = new java.util.concurrent.atomic.AtomicReferenceArray<>(features.size());
                }
            }
        }
        return o;
    }
    /** The entry of each top-level feature. */
    final IntArrayList topEntry = new IntArrayList();
    private final Map<PlacedFeature, Integer> slots = new IdentityHashMap<>();
    /** Nested placed features' entries (patches' and selectors' children), per depth (the kernel's nesting is fixed per depth). */
    @SuppressWarnings("unchecked")
    private final Map<PlacedFeature, Integer>[] nested = new Map[] { null, new IdentityHashMap<>(), new IdentityHashMap<>() };
    private final List<int[]> entries = new ArrayList<>();
    private final IntArrayList kinds = new IntArrayList();
    public int[] fspec;
    final IntArrayList auxList = new IntArrayList();
    public int[] aux;
    public float[] fsin = new float[1];
    private final List<float[]> sins = new ArrayList<>();
    private int sinTotal;
    public final int biomeWords;
    public int[] biomeBits;
    public final int epoch;
    public final StateIds ids;
    public final Preds preds;
    final int minY, height;
    /** Fluids and blocks scheduled ticks name, by the index an effect carries. */
    final List<Fluid> tickFluids = new ArrayList<>();
    final List<Block> tickBlocks = new ArrayList<>();
    /** The leaf states updateLeaves writes have their ids ({@link TreeSpec}). */
    boolean leavesRegistered;
    /** Per top-level feature: why it stays on the CPU (for the status), only the first few. */
    public final List<String> skipped = new ArrayList<>();
    /** Configured features met and placed on the GPU, per kind. */
    public final int[] met = new int[KIND_NAMES.length], placed = new int[KIND_NAMES.length];
    public int oreFeatures;

    private final WorldGenerationContext wctx;
    private final ChunkGenerator generator;
    private final List<Holder<Biome>> biomes;

    private DecorSpec(StateIds ids, ChunkGenerator generator, List<Holder<Biome>> biomes, int minY, int height) {
        this.ids = ids;
        this.preds = new Preds(ids);
        this.epoch = TagEpoch.current();
        this.biomeWords = Math.max(1, (biomes.size() + 31) / 32);
        this.minY = minY;
        this.height = height;
        this.generator = generator;
        this.biomes = biomes;
        this.wctx = new WorldGenerationContext(generator, LevelHeightAccessor.create(minY, height));
    }

    /** The GPU feature slot of a top-level placed feature, or -1. */
    public int slot(PlacedFeature placed) {
        Integer s = slots.get(placed);
        return s == null ? -1 : s;
    }

    /** The configured feature kind of a top-level slot. */
    public int kind(int slot) {
        return kinds.getInt(topEntry.getInt(slot));
    }

    public boolean current() {
        return epoch == TagEpoch.current();
    }

    private final java.util.concurrent.ConcurrentHashMap<Set<Holder<Biome>>, int[]> lists = new java.util.concurrent.ConcurrentHashMap<>();

    /** The GPU features of a chunk whose 3x3 chunks hold {@code biomes}, as (slot, index, step) in vanilla's order. Computed once per set. */
    public int[] features(Set<Holder<Biome>> biomes, java.util.function.Supplier<int[]> compute) {
        int[] l = lists.get(biomes);
        if (l == null) {
            l = compute.get();
            if (lists.size() < 4096) {
                lists.put(Set.copyOf(biomes), l);
            }
        }
        return l;
    }

    // ---- building

    /** Every placed feature of every step the GPU can place. Null when none, or when the placement pipeline that hosts it is off. */
    public static DecorSpec build(ChunkGenerator generator, List<Holder<Biome>> biomes, StateIds ids, int minY, int height) {
        if (!FeaturesCompat.placementPipelineAllowed()) {
            return null;
        }
        DecorSpec s = new DecorSpec(ids, generator, biomes, minY, height);
        // reads outside the build height answer void air
        ids.id(Blocks.VOID_AIR.defaultBlockState());
        ids.id(Blocks.AIR.defaultBlockState());
        List<int[]> bits = new ArrayList<>();
        for (FeatureSorter.StepFeatureData step : ((ChunkGeneratorFeaturesAccessor) generator).ga2$featuresPerStep().get()) {
            for (PlacedFeature pf : step.features()) {
                if (s.slots.containsKey(pf)) {
                    continue;
                }
                String[] why = new String[1];
                String earlier = Probation.off(name(pf));
                if (earlier != null) {
                    if (s.skipped.size() < 200) {
                        s.skipped.add(name(pf) + ": differed from the CPU in an earlier run of this pack (" + earlier + ")");
                    }
                    continue;
                }
                int e;
                try {
                    e = s.entry(pf, 0, why);
                } catch (RuntimeException ex) {
                    e = -1;
                    why[0] = "not understood: " + ex;
                }
                if (e < 0) {
                    if (s.skipped.size() < 200) {
                        s.skipped.add(name(pf) + ": " + why[0]);
                    }
                    continue;
                }
                int[] b = new int[s.biomeWords];
                for (int i = 0; i < biomes.size(); i++) {
                    if (generator.getBiomeGenerationSettings(biomes.get(i)).hasFeature(pf)) {
                        b[i >> 5] |= 1 << (i & 31);
                    }
                }
                bits.add(b);
                s.slots.put(pf, s.features.size());
                s.features.add(pf);
                s.topEntry.add(e);
            }
        }
        if (s.features.isEmpty()) {
            return null;
        }
        s.finish(bits);
        return s;
    }

    private void finish(List<int[]> bits) {
        int n = entries.size();
        int total = n;
        for (int[] e : entries) {
            total += e.length;
        }
        fspec = new int[total];
        int at = n;
        for (int i = 0; i < n; i++) {
            fspec[i] = at;
            System.arraycopy(entries.get(i), 0, fspec, at, entries.get(i).length);
            at += entries.get(i).length;
        }
        aux = auxList.toIntArray();
        if (aux.length == 0) {
            aux = new int[1];
        }
        fsin = new float[Math.max(1, sinTotal)];
        int sat = 0;
        for (float[] f : sins) {
            System.arraycopy(f, 0, fsin, sat, f.length);
            sat += f.length;
        }
        biomeBits = new int[features.size() * biomeWords];
        for (int i = 0; i < features.size(); i++) {
            System.arraycopy(bits.get(i), 0, biomeBits, i * biomeWords, biomeWords);
        }
    }

    static String name(PlacedFeature pf) {
        return pf.feature().unwrapKey().map(k -> k.location().toString()).orElse("inline " + pf.feature().value().feature().getClass().getSimpleName());
    }

    /**
     * The entry of a placed feature at nesting depth {@code depth}, or -1 with the reason in {@code why[0]}. Depth 0 is top-level,
     * with every modifier. Deeper entries take filters only.
     */
    private int entry(PlacedFeature pf, int depth, String[] why) {
        if (depth > 0) {
            Integer known = nested[depth].get(pf);
            if (known != null) {
                return known;
            }
        }
        IntArrayList ops = new IntArrayList();
        String bad = ops(pf.placement(), depth == 0, ops);
        if (bad != null) {
            why[0] = bad;
            return -1;
        }
        ConfiguredFeature<?, ?> cf = pf.feature().value();
        Feature<?> f = cf.feature();
        Class<?> fc = f.getClass();
        IntArrayList params = new IntArrayList();
        int kind;
        String r;
        if (fc == OreFeature.class && f == Feature.ORE) {
            kind = K_ORE;
            oreFeatures++;
            r = depth > 0 ? "an ore inside another feature" : ore((OreConfiguration) cf.config(), params);
        } else if (fc == SimpleBlockFeature.class) {
            kind = K_SIMPLE;
            r = simple((SimpleBlockConfiguration) cf.config(), params);
        } else if (fc == RandomPatchFeature.class) {
            kind = K_PATCH;
            r = depth >= MAX_NEST ? "nested too deep" : patch((RandomPatchConfiguration) cf.config(), depth, params);
        } else if (fc == RandomSelectorFeature.class) {
            kind = K_SELECT;
            r = depth >= MAX_NEST ? "nested too deep" : select((RandomFeatureConfiguration) cf.config(), depth, params);
        } else if (fc == SimpleRandomSelectorFeature.class) {
            kind = K_SSELECT;
            r = depth >= MAX_NEST ? "nested too deep" : simpleSelect((SimpleRandomFeatureConfiguration) cf.config(), depth, params);
        } else if (fc == RandomBooleanSelectorFeature.class) {
            kind = K_BSELECT;
            r = depth >= MAX_NEST ? "nested too deep" : boolSelect((RandomBooleanFeatureConfiguration) cf.config(), depth, params);
        } else if (fc == SpringFeature.class) {
            kind = K_SPRING;
            r = spring((SpringConfiguration) cf.config(), params);
        } else if (fc == net.minecraft.world.level.levelgen.feature.TreeFeature.class) {
            kind = K_TREE;
            r = TreeSpec.build(this, (net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration) cf.config(), params);
        } else if (fc == net.minecraft.world.level.levelgen.feature.SeagrassFeature.class) {
            kind = K_SEAGRASS;
            r = seagrass((net.minecraft.world.level.levelgen.feature.configurations.ProbabilityFeatureConfiguration) cf.config(), params);
        } else if (fc == net.minecraft.world.level.levelgen.feature.KelpFeature.class) {
            kind = K_KELP;
            r = kelp(params);
        } else if (fc == net.minecraft.world.level.levelgen.feature.SeaPickleFeature.class) {
            kind = K_PICKLE;
            r = seaPickle((net.minecraft.world.level.levelgen.feature.configurations.CountConfiguration) cf.config(), params);
        } else if (fc == net.minecraft.world.level.levelgen.feature.MultifaceGrowthFeature.class) {
            kind = K_MULTIFACE;
            r = multiface((net.minecraft.world.level.levelgen.feature.configurations.MultifaceGrowthConfiguration) cf.config(), params);
        } else if (fc == net.minecraft.world.level.levelgen.feature.HugeRedMushroomFeature.class
            || fc == net.minecraft.world.level.levelgen.feature.HugeBrownMushroomFeature.class) {
            kind = K_MUSHROOM;
            r = hugeMushroom((net.minecraft.world.level.levelgen.feature.configurations.HugeMushroomFeatureConfiguration) cf.config(),
                fc == net.minecraft.world.level.levelgen.feature.HugeRedMushroomFeature.class, params);
        } else if (fc == net.minecraft.world.level.levelgen.feature.SnowAndFreezeFeature.class) {
            kind = K_SNOW;
            r = snow(params);
        } else if (fc == net.minecraft.world.level.levelgen.feature.VinesFeature.class) {
            kind = K_VINES;
            r = vines(params);
        } else if (fc == net.minecraft.world.level.levelgen.feature.BlockBlobFeature.class) {
            kind = K_BLOB;
            r = blob((net.minecraft.world.level.levelgen.feature.configurations.BlockStateConfiguration) cf.config(), params);
        } else if (fc == net.minecraft.world.level.levelgen.feature.DiskFeature.class) {
            kind = K_DISK;
            r = disk((net.minecraft.world.level.levelgen.feature.configurations.DiskConfiguration) cf.config(), params);
        } else if (fc == net.minecraft.world.level.levelgen.feature.BlockColumnFeature.class) {
            kind = K_COLUMN;
            r = column((net.minecraft.world.level.levelgen.feature.configurations.BlockColumnConfiguration) cf.config(), params);
        } else {
            why[0] = "feature " + fc.getSimpleName();
            return -1;
        }
        met[kind]++;
        if (r != null) {
            why[0] = KIND_NAMES[kind] + ": " + r;
            return -1;
        }
        placed[kind]++;
        int[] e = new int[HEAD + ops.size() + params.size()];
        e[0] = kind;
        e[1] = ops.size() / 4;
        e[2] = depth;
        e[3] = params.size();
        ops.getElements(0, e, HEAD, ops.size());
        params.getElements(0, e, HEAD + ops.size(), params.size());
        int index = entries.size();
        entries.add(e);
        kinds.add(kind);
        if (depth > 0) {
            nested[depth].put(pf, index);
        }
        return index;
    }

    // ---- placement modifiers

    /** Appends the modifiers' ops. Returns null when the GPU runs them all as vanilla does, else the reason it cannot. */
    private String ops(List<PlacementModifier> mods, boolean top, IntArrayList ops) {
        int n = 0;
        for (PlacementModifier m : mods) {
            Class<?> c = m.getClass();
            if (!c.getName().startsWith("net.minecraft.") || !FeatureTargets.allowed(FeatureTargets.modifierKey(c.getName().replace('.', '/')))) {
                return "modifier " + c.getSimpleName() + " changed by another mod";
            }
            if (!top && c != BlockPredicateFilter.class && c != RarityFilter.class && c != EnvironmentScanPlacement.class) {
                // a nested placed feature has no top feature (a biome filter there throws) and vanilla's nest only filters or scans
                return "nested modifier " + c.getSimpleName();
            }
            int a = 0, b = 0, d = 0, op;
            if (c == CountPlacement.class) {
                op = OP_COUNT;
                a = intProvider(((CountPlacement) m).count);
                if (a < 0) {
                    return "count " + ((CountPlacement) m).count.getClass().getSimpleName();
                }
            } else if (c == InSquarePlacement.class) {
                op = OP_SQUARE;
            } else if (c == HeightRangePlacement.class) {
                op = OP_HEIGHT;
                a = heightProvider(((HeightRangePlacement) m).height);
                if (a < 0) {
                    return "height " + ((HeightRangePlacement) m).height.getClass().getSimpleName();
                }
            } else if (c == BiomeFilter.class) {
                op = OP_BIOME;
            } else if (c == RarityFilter.class) {
                op = OP_RARITY;
                a = Float.floatToRawIntBits(1.0F / (float) ((RarityFilter) m).chance);
            } else if (c == HeightmapPlacement.class) {
                op = OP_HEIGHTMAP;
                a = heightmap(((HeightmapPlacement) m).heightmap);
                if (a < 0) {
                    return "heightmap " + ((HeightmapPlacement) m).heightmap;
                }
            } else if (c == BlockPredicateFilter.class) {
                if (!FeatureTargets.allowed(FeatureTargets.PREDICATE_CALLS)) {
                    return "block predicate filter wrapped by another mod";
                }
                op = OP_FILTER;
                a = predicate(((dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.BlockPredicateFilterAccessor) m).ga2$predicate());
                if (a < 0) {
                    return "block predicate " + lastPredicateProblem;
                }
            } else if (c == EnvironmentScanPlacement.class) {
                if (!FeatureTargets.allowed(FeatureTargets.PREDICATE_CALLS)) {
                    return "block predicate filter wrapped by another mod";
                }
                var e = (dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.EnvironmentScanAccessor) m;
                op = OP_ENV_SCAN;
                a = predicate(e.ga2$target());
                b = a < 0 ? -1 : predicate(e.ga2$allowed());
                if (b < 0) {
                    return "environment scan: block predicate " + lastPredicateProblem;
                }
                int steps = e.ga2$maxSteps(), dy = e.ga2$direction().getStepY();
                if (dy == 0 || steps < 1 || steps > 64) {
                    return "environment scan " + e.ga2$direction() + " " + steps;
                }
                d = dy * steps;
            } else if (c == RandomOffsetPlacement.class) {
                op = OP_OFFSET;
                a = intProvider(((RandomOffsetPlacement) m).xzSpread);
                b = intProvider(((RandomOffsetPlacement) m).ySpread);
                if (a < 0 || b < 0) {
                    return "random offset spread";
                }
            } else if (c == SurfaceWaterDepthFilter.class) {
                op = OP_WATER_DEPTH;
                a = ((SurfaceWaterDepthFilter) m).maxWaterDepth;
            } else if (c == SurfaceRelativeThresholdFilter.class) {
                SurfaceRelativeThresholdFilter s = (SurfaceRelativeThresholdFilter) m;
                op = OP_SURFACE_REL;
                a = heightmap(s.heightmap);
                b = s.minInclusive;
                d = s.maxInclusive;
                if (a < 0) {
                    return "heightmap " + s.heightmap;
                }
            } else if (c == NoiseThresholdCountPlacement.class) {
                NoiseThresholdCountPlacement p = (NoiseThresholdCountPlacement) m;
                op = OP_NOISE_THRESHOLD;
                a = auxList.size();
                long bits = Double.doubleToRawLongBits(p.noiseLevel);
                auxList.add((int) bits);
                auxList.add((int) (bits >>> 32));
                auxList.add(p.belowNoise);
                auxList.add(p.aboveNoise);
            } else if (c == NoiseBasedCountPlacement.class) {
                NoiseBasedCountPlacement p = (NoiseBasedCountPlacement) m;
                op = OP_NOISE_COUNT;
                a = auxList.size();
                long f = Double.doubleToRawLongBits(p.noiseFactor), o = Double.doubleToRawLongBits(p.noiseOffset);
                auxList.add(p.noiseToCountRatio);
                auxList.add((int) f);
                auxList.add((int) (f >>> 32));
                auxList.add((int) o);
                auxList.add((int) (o >>> 32));
            } else {
                return "modifier " + c.getSimpleName();
            }
            ops.add(op);
            ops.add(a);
            ops.add(b);
            ops.add(d);
            n++;
        }
        return n > MAX_OPS ? n + " modifiers" : null;
    }

    /** The kernel's index of a heightmap type, or -1. */
    static int heightmap(Heightmap.Types t) {
        if (t == Heightmap.Types.WORLD_SURFACE_WG) {
            return HM_WS_WG;
        }
        if (t == Heightmap.Types.OCEAN_FLOOR_WG) {
            return HM_OF_WG;
        }
        for (int i = 0; i < Preds.FINAL.length; i++) {
            if (Preds.FINAL[i] == t) {
                return 2 + i;
            }
        }
        return -1;
    }

    // ---- int and height providers (checked against the objects themselves)

    /** The provider in {@link #aux}, or -1 when the GPU does not sample it as vanilla does. */
    int intProvider(IntProvider p) {
        int at = intProvider(p, 0);
        if (at < 0) {
            return -1;
        }
        int off = at;
        return samples(r -> p.sample(r), r -> sampleInt(off, r)) ? at : -1;
    }

    private int intProvider(IntProvider p, int depth) {
        Class<?> c = p.getClass();
        int at = auxList.size();
        if (c == ConstantInt.class) {
            auxList.add(IP_CONST);
            auxList.add(((ConstantInt) p).getValue());
        } else if (c == UniformInt.class) {
            auxList.add(IP_UNIFORM);
            auxList.add(p.getMinValue());
            auxList.add(p.getMaxValue());
        } else if (c == BiasedToBottomInt.class) {
            auxList.add(IP_BIASED);
            auxList.add(p.getMinValue());
            auxList.add(p.getMaxValue());
        } else if (c == ClampedInt.class && depth == 0) {
            ClampedIntAccessor a = (ClampedIntAccessor) p;
            int src = intProvider(a.ga2$source(), 1);
            if (src < 0) {
                return -1;
            }
            at = auxList.size();
            auxList.add(IP_CLAMPED);
            auxList.add(src);
            auxList.add(a.ga2$min());
            auxList.add(a.ga2$max());
        } else if (c == WeightedListInt.class && depth == 0) {
            List<WeightedEntry.Wrapper<IntProvider>> items = ((WeightedListIntAccessor) p).ga2$list().unwrap();
            int[] kids = new int[items.size()];
            long total = 0;
            for (int i = 0; i < kids.length; i++) {
                kids[i] = intProvider(items.get(i).data(), 1);
                if (kids[i] < 0) {
                    return -1;
                }
                total += items.get(i).getWeight().asInt();
            }
            if (total <= 0 || total > Integer.MAX_VALUE) {
                return -1;
            }
            at = auxList.size();
            auxList.add(IP_WEIGHTED);
            auxList.add((int) total);
            auxList.add(kids.length);
            for (int i = 0; i < kids.length; i++) {
                auxList.add(items.get(i).getWeight().asInt());
                auxList.add(kids[i]);
            }
        } else {
            return -1;
        }
        return at;
    }

    /** The GPU's sampling of the int provider at {@code off} (the kernel's {@code dk_int}), on the CPU. */
    int sampleInt(int off, RandomSource r) {
        int[] a = auxList.elements();
        switch (a[off]) {
            case IP_CONST:
                return a[off + 1];
            case IP_UNIFORM:
                return Mth.randomBetweenInclusive(r, a[off + 1], a[off + 2]);
            case IP_BIASED:
                return a[off + 1] + r.nextInt(r.nextInt(a[off + 2] - a[off + 1] + 1) + 1);
            case IP_CLAMPED:
                return Mth.clamp(sampleInt(a[off + 1], r), a[off + 2], a[off + 3]);
            case IP_WEIGHTED: {
                int i = r.nextInt(a[off + 1]);
                for (int k = 0; k < a[off + 2]; k++) {
                    i -= a[off + 3 + k * 2];
                    if (i < 0) {
                        return sampleInt(a[off + 4 + k * 2], r);
                    }
                }
                throw new IllegalStateException();
            }
            default:
                throw new IllegalStateException();
        }
    }

    int heightProvider(HeightProvider h) {
        Class<?> c = h.getClass();
        int at = auxList.size();
        if (c == ConstantHeight.class) {
            auxList.add(HP_CONST);
            auxList.add(((ConstantHeight) h).getValue().resolveY(wctx));
        } else if (c == UniformHeight.class) {
            UniformHeightAccessor a = (UniformHeightAccessor) h;
            auxList.add(HP_UNIFORM);
            auxList.add(a.ga2$min().resolveY(wctx));
            auxList.add(a.ga2$max().resolveY(wctx));
        } else if (c == TrapezoidHeight.class) {
            TrapezoidHeightAccessor a = (TrapezoidHeightAccessor) h;
            auxList.add(HP_TRAPEZOID);
            auxList.add(a.ga2$min().resolveY(wctx));
            auxList.add(a.ga2$max().resolveY(wctx));
            auxList.add(a.ga2$plateau());
        } else if (c == BiasedToBottomHeight.class) {
            BiasedToBottomHeightAccessor a = (BiasedToBottomHeightAccessor) h;
            auxList.add(HP_BIASED);
            auxList.add(a.ga2$min().resolveY(wctx));
            auxList.add(a.ga2$max().resolveY(wctx));
            auxList.add(a.ga2$inner());
        } else if (c == VeryBiasedToBottomHeight.class) {
            VeryBiasedToBottomHeightAccessor a = (VeryBiasedToBottomHeightAccessor) h;
            auxList.add(HP_VERY_BIASED);
            auxList.add(a.ga2$min().resolveY(wctx));
            auxList.add(a.ga2$max().resolveY(wctx));
            auxList.add(a.ga2$inner());
        } else {
            return -1;
        }
        // an empty range warns (once per range for uniform heights) and answers the bottom. Left to the CPU.
        int lo = auxList.getInt(at + 1), hi = c == ConstantHeight.class ? lo : auxList.getInt(at + 2);
        if (lo > hi || (c == BiasedToBottomHeight.class || c == VeryBiasedToBottomHeight.class) && hi - lo - auxList.getInt(at + 3) + 1 <= 0) {
            return -1;
        }
        return samples(r -> h.sample(r, wctx), r -> sampleHeight(at, r)) ? at : -1;
    }

    /** The GPU's sampling of the height provider at {@code off} (the kernel's {@code dk_heightp}), on the CPU. */
    int sampleHeight(int off, RandomSource r) {
        int[] a = auxList.elements();
        int lo = a[off + 1], hi = a[off + 2];
        switch (a[off]) {
            case HP_CONST:
                return lo;
            case HP_UNIFORM:
                return Mth.randomBetweenInclusive(r, lo, hi);
            case HP_TRAPEZOID: {
                int k = hi - lo, pl = a[off + 3];
                if (pl >= k) {
                    return Mth.randomBetweenInclusive(r, lo, hi);
                }
                int l = (k - pl) / 2;
                int i1 = k - l;
                return lo + Mth.randomBetweenInclusive(r, 0, i1) + Mth.randomBetweenInclusive(r, 0, l);
            }
            case HP_BIASED: {
                int inner = a[off + 3];
                int k = r.nextInt(hi - lo - inner + 1);
                return r.nextInt(k + inner) + lo;
            }
            case HP_VERY_BIASED: {
                int inner = a[off + 3];
                int k = Mth.nextInt(r, lo + inner, hi);
                int l = Mth.nextInt(r, lo, k - 1);
                return Mth.nextInt(r, lo, l - 1 + inner);
            }
            default:
                throw new IllegalStateException();
        }
    }

    /** Whether two samplings draw alike over many seeds: the same values, draw counts and random state after. */
    static boolean samples(ToIntFunction<RandomSource> vanilla, ToIntFunction<RandomSource> ours) {
        try {
            for (long seed = 1; seed <= 64; seed++) {
                WorldgenRandom a = new WorldgenRandom(new XoroshiroRandomSource(seed * 0x9E3779B97F4A7C15L));
                WorldgenRandom b = new WorldgenRandom(new XoroshiroRandomSource(seed * 0x9E3779B97F4A7C15L));
                if (vanilla.applyAsInt(a) != ours.applyAsInt(b) || a.getCount() != b.getCount() || a.nextLong() != b.nextLong()) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---- block predicates

    private String lastPredicateProblem;

    /** The predicate's program in {@link #aux} (length, then postfix ops), or -1 with the reason in {@link #lastPredicateProblem}. */
    int predicate(BlockPredicate p) {
        IntArrayList code = new IntArrayList();
        if (!lower(p, code)) {
            return -1;
        }
        // the kernel's value stack holds 16
        int depth = 0, max = 0;
        for (int i = 0; i < code.size(); ) {
            int op = code.getInt(i);
            switch (op) {
                case PR_TRUE, PR_FALSE -> { depth++; i++; }
                case PR_STATE, PR_SURVIVE -> { depth++; i += 5; }
                case PR_BOUNDS -> { depth++; i += 4; }
                case PR_NOT -> i++;
                default -> { depth -= code.getInt(i + 1) - 1; i += 2; }
            }
            max = Math.max(max, depth);
        }
        if (max > 16) {
            lastPredicateProblem = "nested " + max + " deep";
            return -1;
        }
        int at = auxList.size();
        auxList.add(code.size());
        auxList.addAll(code);
        return at;
    }

    private boolean lower(BlockPredicate p, IntArrayList code) {
        Class<?> cls = p.getClass();
        String name = Names.mojangBinaryClass(cls.getName());
        if (!name.startsWith(PRED_PKG) || !FeatureTargets.allowed(PredicatePrograms.predicateKey(name))) {
            lastPredicateProblem = cls.getSimpleName() + (name.startsWith(PRED_PKG) ? " changed by another mod" : "");
            return false;
        }
        switch (name.substring(PRED_PKG.length())) {
            case "AllOfPredicate", "AnyOfPredicate" -> {
                List<BlockPredicate> kids = ((PredicatePrograms.Combining) p).ga2$predicates();
                for (BlockPredicate k : kids) {
                    if (!lower(k, code)) {
                        return false;
                    }
                }
                code.add(name.endsWith("AllOfPredicate") ? PR_ALL : PR_ANY);
                code.add(kids.size());
                return true;
            }
            case "NotPredicate" -> {
                if (!lower(((PredicatePrograms.Negating) p).ga2$predicate(), code)) {
                    return false;
                }
                code.add(PR_NOT);
                return true;
            }
            case "HasSturdyFacePredicate" -> {
                var a = (dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.HasSturdyFacePredicateAccessor) p;
                Vec3i o = a.ga2$offset();
                net.minecraft.core.Direction dir = a.ga2$direction();
                // a state whose shape depends on its position or the level: the CPU decides
                int pred = preds.add("sturdy:" + dir, "sturdy " + dir, s -> Preds.shapeQuestion(s, (st, at) -> st.isFaceSturdy(Preds.NO_READS, at, dir)));
                code.add(PR_STATE);
                code.add(o.getX());
                code.add(o.getY());
                code.add(o.getZ());
                code.add(pred);
                return true;
            }
            case "TrueBlockPredicate" -> {
                code.add(PR_TRUE);
                return true;
            }
            case "InsideWorldBoundsPredicate" -> {
                Vec3i o = ((InsideWorldBoundsPredicateAccessor) p).ga2$offset();
                code.add(PR_BOUNDS);
                code.add(o.getX());
                code.add(o.getY());
                code.add(o.getZ());
                return true;
            }
            case "WouldSurvivePredicate" -> {
                WouldSurvivePredicateAccessor a = (WouldSurvivePredicateAccessor) p;
                Vec3i o = a.ga2$offset();
                int[] rule = survival(a.ga2$state());
                if (rule == null) {
                    String why = SurvivalProbe.why(a.ga2$state());
                    lastPredicateProblem = "survival of " + a.ga2$state() + (why != null ? ": " + why : " reads more than its soil");
                    return false;
                }
                if (rule[0] == SV_ALWAYS || rule[0] == SV_NEVER) {
                    code.add(rule[0] == SV_ALWAYS ? PR_TRUE : PR_FALSE);
                } else if (rule[0] == SV_MULTI) {
                    code.add(PR_SURVIVE);
                    code.add(o.getX());
                    code.add(o.getY());
                    code.add(o.getZ());
                    code.add(rule[1]);
                } else {
                    code.add(PR_STATE);
                    code.add(o.getX());
                    code.add(o.getY() - 1);
                    code.add(o.getZ());
                    code.add(rule[1]);
                }
                return true;
            }
            default -> {
            }
        }
        if (p instanceof StateTestingPredicate && FeatureTargets.allowed(PredicatePrograms.predicateKey(StateTestingPredicate.class.getName()))) {
            Vec3i o = ((PredicatePrograms.StateTesting) p).ga2$offset();
            int pred = statePredicate(p, name.substring(PRED_PKG.length()));
            if (pred < 0) {
                return false;
            }
            code.add(PR_STATE);
            code.add(o.getX());
            code.add(o.getY());
            code.add(o.getZ());
            code.add(pred);
            return true;
        }
        lastPredicateProblem = cls.getSimpleName();
        return false;
    }

    /** The {@link Preds} predicate a block test asks of the state it reads. */
    private int statePredicate(BlockPredicate p, String simple) {
        switch (simple) {
            case "MatchingBlocksPredicate": {
                HolderSet<Block> set = ((PredicatePrograms.MatchingBlocks) p).ga2$blocks();
                return preds.add(new Ident(set), "is " + set, s -> s.is(set));
            }
            case "MatchingBlockTagPredicate": {
                var tag = ((PredicatePrograms.MatchingTag) p).ga2$tag();
                return preds.add("tag:" + tag.location(), "is #" + tag.location(), s -> s.is(tag));
            }
            case "MatchingFluidsPredicate": {
                HolderSet<Fluid> set = ((PredicatePrograms.MatchingFluids) p).ga2$fluids();
                return preds.add(new Ident(set), "fluid in " + set, s -> s.getFluidState().is(set));
            }
            case "SolidPredicate":
                return preds.add("solid", "solid", BlockState::isSolid);
            case "ReplaceablePredicate":
                return preds.add("replaceable", "replaceable", BlockState::canBeReplaced);
            default:
                lastPredicateProblem = simple;
                return -1;
        }
    }

    /** An identity key (holder sets compare by contents). */
    private record Ident(Object o) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Ident i && i.o == o;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(o);
        }
    }

    /**
     * How {@code plant} survives at a position: { SV_ALWAYS }, { SV_NEVER }, { SV_BELOW, predicate of the block below }, or
     * { SV_MULTI, offset of its table in aux } when its rule reads a few blocks around it ({@link SurvivalProbe.Multi}). Null when
     * the rule reads anything else or does not fit a table ({@link SurvivalProbe}).
     */
    int[] survival(BlockState plant) {
        if (!SurvivalProbe.soilOnly(plant, minY, height)) {
            SurvivalProbe.Multi m = SurvivalProbe.multi(plant, minY, height);
            if (m == null) {
                return null;
            }
            int at = auxList.size();
            auxList.add(m.offsets.length);
            int stride = 1;
            for (int i = 0; i < m.offsets.length; i++) {
                int ii = i;
                auxList.add(m.offsets[i].getX());
                auxList.add(m.offsets[i].getY());
                auxList.add(m.offsets[i].getZ());
                auxList.add(preds.addValue("survive:" + System.identityHashCode(plant) + ":" + plant + ":" + i, s -> m.classOf(ii, s)));
                auxList.add(stride);
                stride *= m.counts[i];
            }
            long[] bits = m.table.toLongArray();
            for (int w = 0; w < (stride + 31) / 32; w++) {
                int word = w >> 1;
                auxList.add(word < bits.length ? (int) (bits[word] >>> ((w & 1) * 32)) : 0);
            }
            return new int[] { SV_MULTI, at };
        }
        int p = preds.add("survive:" + System.identityHashCode(plant) + ":" + plant, "soil for " + plant,
            soil -> SurvivalProbe.survives(plant, soil, minY, height));
        return new int[] { SV_BELOW, p };
    }

    // ---- block state providers

    /** The provider in {@link #aux}, each state a plant record. -1 when the GPU does not draw it as vanilla does. */
    int stateProvider(BlockStateProvider p, int rec) {
        Class<?> c = p.getClass();
        int at = auxList.size();
        if (c == SimpleStateProvider.class) {
            auxList.add(SP_SIMPLE);
            if (!record(((SimpleStateProviderAccessor) p).ga2$state(), rec)) {
                return -1;
            }
        } else if (c == WeightedStateProvider.class) {
            List<WeightedEntry.Wrapper<BlockState>> items = ((WeightedStateProviderAccessor) p).ga2$list().unwrap();
            long total = 0;
            for (WeightedEntry.Wrapper<BlockState> w : items) {
                total += w.getWeight().asInt();
            }
            if (total <= 0 || total > Integer.MAX_VALUE) {
                return -1;
            }
            auxList.add(SP_WEIGHTED);
            auxList.add((int) total);
            auxList.add(items.size());
            for (WeightedEntry.Wrapper<BlockState> w : items) {
                auxList.add(w.getWeight().asInt());
                if (!record(w.data(), rec)) {
                    return -1;
                }
            }
        } else if (c == RotatedBlockProvider.class) {
            Block b = ((RotatedBlockProviderAccessor) p).ga2$block();
            auxList.add(SP_ROTATED);
            for (Direction.Axis axis : Direction.Axis.values()) {
                if (!record(b.defaultBlockState().setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS, axis), rec)) {
                    return -1;
                }
            }
        } else if (c == net.minecraft.world.level.levelgen.feature.stateproviders.RandomizedIntStateProvider.class) {
            at = randomizedInt((dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.RandomizedIntStateProviderAccessor) p, rec);
            if (at < 0) {
                return -1;
            }
        } else {
            return -1;
        }
        int off = at;
        return samples(r -> ids.id(p.getState(r, net.minecraft.core.BlockPos.ZERO)), r -> ids.id(sampleState(off, r))) ? at : -1;
    }

    /** {@link #SP_RANDINT}: the source's records, then per record the state with each value of the property (when it has one). */
    private int randomizedInt(dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.RandomizedIntStateProviderAccessor p, int rec) {
        BlockStateProvider src = p.ga2$source();
        if (src.getClass() == net.minecraft.world.level.levelgen.feature.stateproviders.RandomizedIntStateProvider.class) {
            return -1;
        }
        int in = stateProvider(src, rec);
        net.minecraft.util.valueproviders.IntProvider values = p.ga2$values();
        int iv = in < 0 ? -1 : intProvider(values);
        if (iv < 0) {
            return -1;
        }
        int lo = values.getMinValue(), n = values.getMaxValue() - lo + 1;
        if (n <= 0 || n > 64) {
            return -1;
        }
        int[] recs = records(in);
        int at = auxList.size();
        auxList.add(SP_RANDINT);
        auxList.add(in);
        auxList.add(iv);
        auxList.add(lo);
        auxList.add(n);
        int rows = auxList.size();
        for (int i = 0; i < recs.length * (1 + n); i++) {
            auxList.add(0);
        }
        String name = p.ga2$propertyName();
        for (int q = 0; q < recs.length; q++) {
            BlockState s = ids.state(auxList.getInt(recs[q]));
            net.minecraft.world.level.block.state.properties.IntegerProperty prop = null;
            for (net.minecraft.world.level.block.state.properties.Property<?> pp : s.getProperties()) {
                if (pp instanceof net.minecraft.world.level.block.state.properties.IntegerProperty ip && pp.getName().equals(name)) {
                    prop = ip;
                    break;
                }
            }
            int row = rows + q * (1 + n);
            if (prop == null) {
                continue;
            }
            auxList.set(row, 1);
            for (int v = lo; v < lo + n; v++) {
                if (!prop.getPossibleValues().contains(v)) {
                    return -1;
                }
                int r = auxList.size();
                if (!record(s.setValue(prop, v), rec)) {
                    return -1;
                }
                auxList.set(row + 1 + (v - lo), r);
            }
        }
        return at;
    }

    /** The record offsets of a simple, weighted or rotated provider, in its order. */
    private int[] records(int off) {
        int[] a = auxList.elements();
        return switch (a[off]) {
            case SP_SIMPLE -> new int[] { off + 1 };
            case SP_WEIGHTED -> {
                int[] r = new int[a[off + 2]];
                for (int k = 0; k < r.length; k++) {
                    r[k] = off + 4 + k * 5;
                }
                yield r;
            }
            case SP_ROTATED -> new int[] { off + 1, off + 5, off + 9 };
            default -> throw new IllegalStateException();
        };
    }

    /** The index of record {@code rec} in its provider's {@link #records}. */
    private static int recordIndex(int[] a, int off, int rec) {
        return switch (a[off]) {
            case SP_SIMPLE -> 0;
            case SP_WEIGHTED -> (rec - off - 4) / 5;
            default -> (rec - off - 1) / 4;
        };
    }

    /** Appends a record of kind {@code rec} ({@link #REC_PLANT} ...). False when the GPU cannot place the state. */
    private boolean record(BlockState s, int rec) {
        int id = ids.id(s);
        if (id < 0) {
            return false;
        }
        if (rec == REC_AXIS) {
            auxList.add(id);
            for (Direction.Axis axis : Direction.Axis.values()) {
                int a = ids.id(s.trySetValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS, axis));
                if (a < 0) {
                    return false;
                }
                auxList.add(a);
            }
            return true;
        }
        if (rec == REC_LEAF) {
            boolean wet = s.hasProperty(BlockStateProperties.WATERLOGGED);
            int dry = wet ? ids.id(s.setValue(BlockStateProperties.WATERLOGGED, false)) : id;
            int water = wet ? ids.id(s.setValue(BlockStateProperties.WATERLOGGED, true)) : id;
            if (dry < 0 || water < 0) {
                return false;
            }
            auxList.add(dry);
            auxList.add(water);
            auxList.add(wet ? 1 : 0);
            auxList.add(0);
            return true;
        }
        int mode = SV_ALWAYS, pred = 0, upper = -1;
        if (rec == REC_PLANT) {
            int[] rule = survival(s);
            if (rule == null) {
                return false;
            }
            mode = rule[0];
            pred = rule.length > 1 ? rule[1] : 0;
            if (s.getBlock() instanceof DoublePlantBlock) {
                if (s.hasProperty(BlockStateProperties.WATERLOGGED) || s.getValue(DoublePlantBlock.HALF) != DoubleBlockHalf.LOWER) {
                    return false;
                }
                upper = ids.id(s.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER));
                if (upper < 0) {
                    return false;
                }
            }
        }
        auxList.add(id);
        auxList.add(mode);
        auxList.add(pred);
        auxList.add(upper);
        return true;
    }

    /** The GPU's draw of the state provider at {@code off} (the kernel's {@code dk_state}), on the CPU. */
    BlockState sampleState(int off, RandomSource r) {
        return ids.state(auxList.getInt(sampleRecord(off, r)));
    }

    /** The offset of the record the GPU draws from the provider at {@code off}. */
    private int sampleRecord(int off, RandomSource r) {
        int[] a = auxList.elements();
        switch (a[off]) {
            case SP_SIMPLE:
                return off + 1;
            case SP_WEIGHTED: {
                int i = r.nextInt(a[off + 1]);
                for (int k = 0; k < a[off + 2]; k++) {
                    i -= a[off + 3 + k * 5];
                    if (i < 0) {
                        return off + 4 + k * 5;
                    }
                }
                throw new IllegalStateException();
            }
            case SP_ROTATED:
                return off + 1 + r.nextInt(3) * 4;
            case SP_RANDINT: {
                int in = a[off + 1], rec = sampleRecord(in, r), n = a[off + 4];
                int row = off + 5 + recordIndex(a, in, rec) * (1 + n);
                if (a[row] == 0) {
                    return rec;
                }
                return a[row + 1 + sampleInt(a[off + 2], r) - a[off + 3]];
            }
            default:
                throw new IllegalStateException();
        }
    }

    // ---- kinds

    /** Appends the ore's parameters (size, chance, targets, sine offset, (predicate, state) each). Null when the GPU places the ore exactly. */
    private String ore(OreConfiguration oc, IntArrayList params) {
        if (!FeaturesCompat.fastPathAllowed("ore") || dev.denismasterherobrine.generatoraccelerator2.features.fast.FeatureWraps.oreVetoed(oc)) {
            return "changed by another mod";
        }
        if (oc.size < 1 || oc.size > MAX_SIZE) {
            return "size " + oc.size;
        }
        int nt = oc.targetStates.size();
        if (nt == 0 || nt > MAX_TARGETS) {
            return nt + " targets";
        }
        int[] tg = new int[nt * 2];
        for (int i = 0; i < nt; i++) {
            OreConfiguration.TargetBlockState t = oc.targetStates.get(i);
            Class<?> rc = t.target.getClass();
            if (rc != TagMatchTest.class && rc != BlockMatchTest.class && rc != BlockStateMatchTest.class) {
                return "rule test " + rc.getSimpleName();
            }
            if (t.state.isAir()) {
                return "places air";
            }
            for (BlockState a : airStates()) {
                if (t.target.test(a, NO_RANDOM)) {
                    return "its rule matches air";
                }
            }
            int id = ids.id(t.state);
            if (id < 0) {
                return "too many block states";
            }
            var rule = t.target;
            tg[i * 2] = preds.add(new Ident(rule), "rule " + rule.getClass().getSimpleName(), s -> rule.test(s, NO_RANDOM));
            tg[i * 2 + 1] = id;
        }
        // OreFeature.doPlace's bitset index space must fit (its overshoot past width * height * width included)
        float f1 = (float) oc.size / 8.0F;
        int i = Mth.ceil(((float) oc.size / 16.0F * 2.0F + 1.0F) / 2.0F);
        int c1 = Mth.ceil(f1);
        int width = 2 * (c1 + i), tall = 2 * (2 + i);
        int reach = (int) Math.ceil(oc.size / 16.0 + 0.5) + 1;
        int ix = 2 * c1 + i + reach, iy = reach + 2 + i;
        if ((long) ix + (long) iy * width + (long) ix * width * tall >= L2) {
            return "size " + oc.size + " beyond the GPU's bitset";
        }
        float[] sin = new float[oc.size];
        for (int k = 0; k < oc.size; k++) {
            sin[k] = Mth.sin((float) Math.PI * ((float) k / (float) oc.size)) + 1.0F;
        }
        params.add(oc.size);
        params.add(Float.floatToRawIntBits(oc.discardChanceOnAirExposure));
        params.add(nt);
        params.add(sinTotal);
        params.addElements(params.size(), tg);
        sins.add(sin);
        sinTotal += sin.length;
        return null;
    }

    private static List<BlockState> AIR_STATES;

    private static List<BlockState> airStates() {
        List<BlockState> a = AIR_STATES;
        if (a == null) {
            a = new ArrayList<>();
            for (Block b : BuiltInRegistries.BLOCK) {
                for (BlockState s : b.getStateDefinition().getPossibleStates()) {
                    if (s.isAir()) {
                        a.add(s);
                    }
                }
            }
            AIR_STATES = a;
        }
        return a;
    }

    /** Simple block: (provider) with plant records. */
    private String simple(SimpleBlockConfiguration c, IntArrayList params) {
        if (!FeaturesCompat.fastPathAllowed("simpleBlock")) {
            return "changed by another mod";
        }
        int p = stateProvider(c.toPlace(), REC_PLANT);
        if (p < 0) {
            return "provider " + c.toPlace().getClass().getSimpleName();
        }
        params.add(p);
        return null;
    }

    private boolean composites() {
        return FeatureTargets.allowed("gpuDecorComposites");
    }

    /** Random patch: (tries, xz spread + 1, y spread + 1, nested entry). */
    private String patch(RandomPatchConfiguration c, int depth, IntArrayList params) {
        if (!composites()) {
            return "changed by another mod";
        }
        String[] why = new String[1];
        int e = entry(c.feature().value(), depth + 1, why);
        if (e < 0) {
            return "its feature: " + why[0];
        }
        params.add(c.tries());
        params.add(c.xzSpread() + 1);
        params.add(c.ySpread() + 1);
        params.add(e);
        return null;
    }

    /** Random selector: (count, default entry, (chance bits, entry) each). */
    private String select(RandomFeatureConfiguration c, int depth, IntArrayList params) {
        if (!composites()) {
            return "changed by another mod";
        }
        String[] why = new String[1];
        int def = entry(c.defaultFeature.value(), depth + 1, why);
        if (def < 0) {
            return "its default: " + why[0];
        }
        params.add(c.features.size());
        params.add(def);
        for (WeightedPlacedFeature w : c.features) {
            int e = entry(w.feature.value(), depth + 1, why);
            if (e < 0) {
                return "a choice: " + why[0];
            }
            params.add(Float.floatToRawIntBits(w.chance));
            params.add(e);
        }
        return null;
    }

    /** Simple random selector: (count, entry each). */
    private String simpleSelect(SimpleRandomFeatureConfiguration c, int depth, IntArrayList params) {
        if (!composites()) {
            return "changed by another mod";
        }
        String[] why = new String[1];
        List<Holder<PlacedFeature>> list = c.features.stream().toList();
        if (list.isEmpty()) {
            return "no features";
        }
        params.add(list.size());
        for (Holder<PlacedFeature> h : list) {
            int e = entry(h.value(), depth + 1, why);
            if (e < 0) {
                return "a choice: " + why[0];
            }
            params.add(e);
        }
        return null;
    }

    /** Random boolean selector: (true entry, false entry). */
    private String boolSelect(RandomBooleanFeatureConfiguration c, int depth, IntArrayList params) {
        if (!composites()) {
            return "changed by another mod";
        }
        String[] why = new String[1];
        int t = entry(c.featureTrue.value(), depth + 1, why);
        if (t < 0) {
            return "its true feature: " + why[0];
        }
        int f = entry(c.featureFalse.value(), depth + 1, why);
        if (f < 0) {
            return "its false feature: " + why[0];
        }
        params.add(t);
        params.add(f);
        return null;
    }

    /** Spring: (state written, fluid tick index, requires a block below, rock count, hole count, valid-block predicate). */
    private String spring(SpringConfiguration c, IntArrayList params) {
        if (!FeaturesCompat.fastPathAllowed("spring")) {
            return "changed by another mod";
        }
        BlockState legacy = c.state.createLegacyBlock();
        int id = ids.id(legacy);
        if (id < 0) {
            return "too many block states";
        }
        Fluid fluid = c.state.getType();
        int tick = tickFluids.indexOf(fluid);
        if (tick < 0) {
            tick = tickFluids.size();
            tickFluids.add(fluid);
        }
        HolderSet<Block> valid = c.validBlocks;
        params.add(id);
        params.add(tick);
        params.add(c.requiresBlockBelow ? 1 : 0);
        params.add(c.rockCount);
        params.add(c.holeCount);
        params.add(preds.add(new Ident(valid), "is " + valid, s -> s.is(valid)));
        return null;
    }

    private int isBlock(Block b) {
        return preds.add("is:" + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b), "is " + b, s -> s.is(b));
    }

    /** Appends (state, survival mode, its predicate). False when the state has no id or {@link #survival} has no GPU form for it. */
    private boolean plant(BlockState s, IntArrayList params) {
        int id = ids.id(s);
        int[] rule = id < 0 ? null : survival(s);
        if (rule == null) {
            return false;
        }
        params.add(id);
        params.add(rule[0]);
        params.add(rule.length > 1 ? rule[1] : 0);
        return true;
    }

    /** Seagrass: (probability bits, seagrass plant, tall seagrass plant, its upper half, water). */
    private String seagrass(net.minecraft.world.level.levelgen.feature.configurations.ProbabilityFeatureConfiguration c, IntArrayList params) {
        if (!FeaturesCompat.fastPathAllowed("seagrass")) {
            return "changed by another mod";
        }
        BlockState tall = Blocks.TALL_SEAGRASS.defaultBlockState();
        int upper = ids.id(tall.setValue(net.minecraft.world.level.block.TallSeagrassBlock.HALF, DoubleBlockHalf.UPPER));
        params.add(Float.floatToRawIntBits(c.probability));
        if (upper < 0 || !plant(Blocks.SEAGRASS.defaultBlockState(), params) || !plant(tall, params)) {
            return "seagrass survival or states";
        }
        params.add(upper);
        params.add(isBlock(Blocks.WATER));
        return null;
    }

    /** Kelp: (water, kelp plant, kelp's survival mode and predicate, is kelp, the kelp tip with age 20..23). */
    private String kelp(IntArrayList params) {
        if (!FeaturesCompat.fastPathAllowed("kelp")) {
            return "changed by another mod";
        }
        params.add(isBlock(Blocks.WATER));
        BlockState kelp = Blocks.KELP.defaultBlockState();
        if (!plant(Blocks.KELP_PLANT.defaultBlockState(), params) || !plant(kelp, params)) {
            return "kelp survival or states";
        }
        // plant() appended the tip's own id. Only the tip's rule (mode, predicate) is used.
        params.removeInt(4);
        params.add(isBlock(Blocks.KELP));
        for (int age = 20; age < 24; age++) {
            int id = ids.id(kelp.setValue(net.minecraft.world.level.block.KelpBlock.AGE, age));
            if (id < 0) {
                return "too many block states";
            }
            params.add(id);
        }
        return null;
    }

    /** Sea pickles: (count provider, water, the plant with 1..4 pickles). */
    private String seaPickle(net.minecraft.world.level.levelgen.feature.configurations.CountConfiguration c, IntArrayList params) {
        if (!FeatureTargets.allowed("gpuDecorLeaves")) {
            return "changed by another mod";
        }
        int count = intProvider(c.count());
        if (count < 0) {
            return "count " + c.count().getClass().getSimpleName();
        }
        params.add(count);
        params.add(isBlock(Blocks.WATER));
        for (int n = 1; n <= 4; n++) {
            if (!plant(Blocks.SEA_PICKLE.defaultBlockState().setValue(net.minecraft.world.level.block.SeaPickleBlock.PICKLES, n), params)) {
                return "sea pickle survival or states";
            }
        }
        return null;
    }

    /**
     * Multiface growth of glow lichen (the default spreader): (face table, code table, air or water, can be placed on, attach per direction
     * x6, search range, spreading chance bits, valid direction count, the directions x6, waterloggable, can be replaced, the default
     * state's code). A state's code is its faces (bit per direction ordinal) and waterlogged (bit 6).
     */
    private String multiface(net.minecraft.world.level.levelgen.feature.configurations.MultifaceGrowthConfiguration c, IntArrayList params) {
        if (!FeaturesCompat.fastPathAllowed("multiface") || !FeatureTargets.allowed("gpuMultiface")) {
            return "changed by another mod";
        }
        net.minecraft.world.level.block.MultifaceBlock block = c.placeBlock;
        if (block.getClass() != net.minecraft.world.level.block.GlowLichenBlock.class) {
            return "block " + block.getClass().getSimpleName();
        }
        BlockState def = block.defaultBlockState();
        boolean wl = def.hasProperty(BlockStateProperties.WATERLOGGED);
        int table = auxList.size();
        for (int code = 0; code < 128; code++) {
            BlockState s = def;
            for (Direction d : Direction.values()) {
                s = s.setValue(net.minecraft.world.level.block.MultifaceBlock.getFaceProperty(d), (code >> d.ordinal() & 1) != 0);
            }
            int id;
            if (wl) {
                id = ids.id(s.setValue(BlockStateProperties.WATERLOGGED, (code & 64) != 0));
            } else {
                id = (code & 64) != 0 ? -1 : ids.id(s);
            }
            if (id < 0 && (wl || (code & 64) == 0)) {
                return "too many block states";
            }
            auxList.add(id);
        }
        int faces = preds.addValue("multiface:" + System.identityHashCode(block), s -> mfCode(block, s));
        params.add(faces);
        params.add(table);
        params.add(preds.add("airOrWater", "air or water", s -> s.isAir() || s.is(Blocks.WATER)));
        params.add(preds.add(new Ident(c.canBePlacedOn), "is " + c.canBePlacedOn, s -> s.is(c.canBePlacedOn)));
        for (Direction d : Direction.values()) {
            params.add(preds.add("attach:" + d, "attachable from " + d,
                s -> Preds.shapeQuestion(s, (st, at) -> net.minecraft.world.level.block.MultifaceBlock.canAttachTo(Preds.NO_READS, d, at, st))));
        }
        params.add(c.searchRange);
        params.add(Float.floatToRawIntBits(c.chanceOfSpreading));
        var valid = ((dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.MultifaceGrowthConfigurationAccessor) c).ga2$validDirections();
        params.add(valid.size());
        for (int i = 0; i < 6; i++) {
            params.add(i < valid.size() ? valid.get(i).ordinal() : 0);
        }
        params.add(wl ? 1 : 0);
        params.add(preds.add("mfReplace:" + System.identityHashCode(block), "replaceable by " + block,
            s -> s.isAir() || s.is(block) || s.is(Blocks.WATER) && s.getFluidState().isSource()));
        params.add(mfCode(block, def));
        return null;
    }

    /** MultifaceBlock.canAttachTo of the block read, from the side facing {@code d}. */
    private int attach(Direction d) {
        return preds.add("attach:" + d, "attachable from " + d,
            s -> Preds.shapeQuestion(s, (st, at) -> net.minecraft.world.level.block.MultifaceBlock.canAttachTo(Preds.NO_READS, d, at, st)));
    }

    /** Vines: (attachable, per direction up, north, south, west, east), then the vine facing each. */
    private String vines(IntArrayList params) {
        if (!FeatureTargets.allowed("gpuDecorLeaves") || !FeatureTargets.allowed("gpuMultiface")) {
            return "changed by another mod";
        }
        for (int d = 1; d < 6; d++) {
            params.add(attach(Direction.values()[d]));
        }
        for (int d = 1; d < 6; d++) {
            int id = ids.id(Blocks.VINE.defaultBlockState().setValue(net.minecraft.world.level.block.VineBlock.getPropertyForFace(Direction.values()[d]), true));
            if (id < 0) {
                return "too many block states";
            }
            params.add(id);
        }
        return null;
    }

    /**
     * Snow and freeze over the chunk's columns: (freezable water, ice, snow, air or snow, snow's survival mode and predicate). The
     * temperatures come from the surface's tables. Block light is taken to be below 10, which the CPU checks ({@link #RT_LIGHT}).
     */
    private String snow(IntArrayList params) {
        if (!FeaturesCompat.fastPathAllowed("snowAndFreeze") || !FeatureTargets.allowed("gpuClimate")) {
            return "changed by another mod";
        }
        BlockState snow = Blocks.SNOW.defaultBlockState();
        int ice = ids.id(Blocks.ICE.defaultBlockState()), sn = ids.id(snow);
        int[] rule = survival(snow);
        if (ice < 0 || sn < 0 || rule == null) {
            return "snow survival or states";
        }
        params.add(preds.add("freezable", "water to freeze", s -> s.getFluidState().getType() == net.minecraft.world.level.material.Fluids.WATER
            && s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock));
        params.add(ice);
        params.add(sn);
        params.add(preds.add("airOrSnow", "air or snow", s -> s.isAir() || s.is(Blocks.SNOW)));
        params.add(rule[0]);
        params.add(rule.length > 1 ? rule[1] : 0);
        return null;
    }

    /**
     * Huge mushroom: (red, foliage radius, stem, cap table, soil, solid render). The cap table holds the cap state per face bits
     * (up << 4 | west << 3 | east << 2 | north << 1 | south), as makeCap sets them when the provider's state has the properties.
     */
    private String hugeMushroom(net.minecraft.world.level.levelgen.feature.configurations.HugeMushroomFeatureConfiguration c, boolean red, IntArrayList params) {
        if (!FeatureTargets.allowed("gpuHugeMushroom")) {
            return "changed by another mod";
        }
        // providers that draw (or read the position) would draw per block. Only fixed states are allowed.
        if (c.capProvider.getClass() != SimpleStateProvider.class || c.stemProvider.getClass() != SimpleStateProvider.class) {
            return "providers " + Names.mojangSimpleName(c.capProvider.getClass()) + ", " + Names.mojangSimpleName(c.stemProvider.getClass());
        }
        BlockState cap = ((SimpleStateProviderAccessor) c.capProvider).ga2$state(), stem = ((SimpleStateProviderAccessor) c.stemProvider).ga2$state();
        int stemId = ids.id(stem);
        if (stemId < 0) {
            return "too many block states";
        }
        boolean faces = cap.hasProperty(net.minecraft.world.level.block.HugeMushroomBlock.WEST) && cap.hasProperty(net.minecraft.world.level.block.HugeMushroomBlock.EAST)
            && cap.hasProperty(net.minecraft.world.level.block.HugeMushroomBlock.NORTH) && cap.hasProperty(net.minecraft.world.level.block.HugeMushroomBlock.SOUTH)
            && (!red || cap.hasProperty(net.minecraft.world.level.block.HugeMushroomBlock.UP));
        int table = auxList.size();
        for (int idx = 0; idx < 32; idx++) {
            BlockState s = cap;
            if (faces) {
                if (red) {
                    s = s.setValue(net.minecraft.world.level.block.HugeMushroomBlock.UP, (idx & 16) != 0);
                }
                s = s.setValue(net.minecraft.world.level.block.HugeMushroomBlock.WEST, (idx & 8) != 0).setValue(net.minecraft.world.level.block.HugeMushroomBlock.EAST, (idx & 4) != 0)
                    .setValue(net.minecraft.world.level.block.HugeMushroomBlock.NORTH, (idx & 2) != 0).setValue(net.minecraft.world.level.block.HugeMushroomBlock.SOUTH, (idx & 1) != 0);
            }
            int id = ids.id(s);
            if (id < 0) {
                return "too many block states";
            }
            auxList.add(id);
        }
        params.add(red ? 1 : 0);
        params.add(c.foliageRadius);
        params.add(stemId);
        params.add(table);
        params.add(preds.add("mushroomSoil", "soil for a huge mushroom", s -> s.is(net.minecraft.tags.BlockTags.DIRT) || s.is(net.minecraft.tags.BlockTags.MUSHROOM_GROW_BLOCK)));
        params.add(preds.add("solidRender", "solid render", s -> Preds.shapeQuestion(s, (st, at) -> st.isSolidRender(Preds.NO_READS, at))));
        return null;
    }

    /** Block blob: (state, stop: not air and dirt or stone). */
    private String blob(net.minecraft.world.level.levelgen.feature.configurations.BlockStateConfiguration c, IntArrayList params) {
        if (!FeatureTargets.allowed("gpuDecorLeaves")) {
            return "changed by another mod";
        }
        int id = ids.id(c.state);
        if (id < 0) {
            return "too many block states";
        }
        params.add(id);
        params.add(preds.add("blobStop", "a blob's ground", s -> !s.isAir() && (s.is(net.minecraft.tags.BlockTags.DIRT) || s.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD))));
        return null;
    }

    /** A multiface state's code (faces, waterlogged in bit 6), -1 for another block. */
    private static int mfCode(Block block, BlockState s) {
        if (!s.is(block)) {
            return -1;
        }
        int code = 0;
        for (Direction d : Direction.values()) {
            if (net.minecraft.world.level.block.MultifaceBlock.hasFace(s, d)) {
                code |= 1 << d.ordinal();
            }
        }
        if (s.hasProperty(BlockStateProperties.WATERLOGGED) && s.getValue(BlockStateProperties.WATERLOGGED)) {
            code |= 64;
        }
        return code;
    }

    /** Disk: (radius provider, half height, target, fallback provider, rule count, (predicate, provider) each). */
    private String disk(net.minecraft.world.level.levelgen.feature.configurations.DiskConfiguration c, IntArrayList params) {
        if (!FeaturesCompat.fastPathAllowed("disk") || !FeatureTargets.allowed(FeatureTargets.PREDICATE_CALLS)) {
            return "changed by another mod";
        }
        var rb = c.stateProvider();
        if (rb.getClass() != net.minecraft.world.level.levelgen.feature.stateproviders.RuleBasedBlockStateProvider.class) {
            return "provider " + rb.getClass().getSimpleName();
        }
        int radius = intProvider(c.radius());
        if (radius < 0) {
            return "radius " + c.radius().getClass().getSimpleName();
        }
        int target = predicate(c.target());
        if (target < 0) {
            return "target " + lastPredicateProblem;
        }
        int fallback = stateProvider(rb.fallback(), REC_PLAIN);
        if (fallback < 0) {
            return "fallback " + rb.fallback().getClass().getSimpleName();
        }
        params.add(radius);
        params.add(c.halfHeight());
        params.add(target);
        params.add(fallback);
        params.add(rb.rules().size());
        for (var rule : rb.rules()) {
            int p = predicate(rule.ifTrue());
            if (p < 0) {
                return "rule " + lastPredicateProblem;
            }
            int s = stateProvider(rule.then(), REC_PLAIN);
            if (s < 0) {
                return "rule provider " + rule.then().getClass().getSimpleName();
            }
            params.add(p);
            params.add(s);
        }
        return null;
    }

    /** Block column: (dx, dy, dz, allowed placement, prioritize tip, layer count, (height provider, state provider) each). */
    private String column(net.minecraft.world.level.levelgen.feature.configurations.BlockColumnConfiguration c, IntArrayList params) {
        if (!FeatureTargets.allowed("gpuDecorLeaves") || !FeatureTargets.allowed(FeatureTargets.PREDICATE_CALLS)) {
            return "changed by another mod";
        }
        if (c.layers().isEmpty() || c.layers().size() > 8) {
            return c.layers().size() + " layers";
        }
        int allowed = predicate(c.allowedPlacement());
        if (allowed < 0) {
            return "block predicate " + lastPredicateProblem;
        }
        Direction d = c.direction();
        params.add(d.getStepX());
        params.add(d.getStepY());
        params.add(d.getStepZ());
        params.add(allowed);
        params.add(c.prioritizeTip() ? 1 : 0);
        params.add(c.layers().size());
        for (var layer : c.layers()) {
            int h = intProvider(layer.height());
            if (h < 0) {
                return "layer height " + layer.height().getClass().getSimpleName();
            }
            int s = stateProvider(layer.state(), REC_PLAIN);
            if (s < 0) {
                return "layer provider " + layer.state().getClass().getSimpleName();
            }
            params.add(h);
            params.add(s);
        }
        return null;
    }

    // ---- the ore kind's value checks

    /** How an ore result is checked by value where a block it read changed ({@link #check}). */
    public record Check(dev.denismasterherobrine.generatoraccelerator2.features.fast.OreTargets targets, boolean draws, BlockState[] states,
                        boolean repairable) {
        /** The state the first matching target writes. */
        public BlockState first(int tm) {
            return states[Integer.numberOfTrailingZeros(tm)];
        }
    }

    /**
     * Whether each block the ore visits is decided by itself: no draws per block, and no write that changes another block's air exposure
     * (no target state is air, no target matches air).
     */
    private static boolean repairable(dev.denismasterherobrine.generatoraccelerator2.features.fast.OreTargets t, BlockState[] states, boolean draws) {
        if (draws) {
            return false;
        }
        for (BlockState s : states) {
            if (s.isAir()) {
                return false;
            }
        }
        for (BlockState a : new BlockState[] { net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
            net.minecraft.world.level.block.Blocks.CAVE_AIR.defaultBlockState(), net.minecraft.world.level.block.Blocks.VOID_AIR.defaultBlockState() }) {
            if (t.mask(a) != 0) {
                return false;
            }
        }
        return true;
    }

    private final java.util.concurrent.ConcurrentHashMap<Integer, java.util.Optional<Check>> checks = new java.util.concurrent.ConcurrentHashMap<>();

    /** The value check of the ore in {@code slot}, or null when only an unchanged block counts (more than 5 targets). */
    public Check check(int slot) {
        return checks.computeIfAbsent(slot, s -> {
            OreConfiguration oc = (OreConfiguration) features.get(s).feature().value().config();
            var t = dev.denismasterherobrine.generatoraccelerator2.features.fast.OreTargets.of(oc);
            if (t == null || oc.targetStates.size() > 5) {
                return java.util.Optional.empty();
            }
            BlockState[] st = new BlockState[oc.targetStates.size()];
            for (int i = 0; i < st.length; i++) {
                st[i] = oc.targetStates.get(i).state;
            }
            float c = oc.discardChanceOnAirExposure;
            boolean draws = c > 0.0F && c < 1.0F;
            return java.util.Optional.of(new Check(t, draws, st, repairable(t, st, draws)));
        }).orElse(null);
    }

    /** Whether the ore in {@code slot} checks air exposure (its discard chance is above 0). */
    public boolean airChecks(int slot) {
        int[] e = entries.get(topEntry.getInt(slot));
        return Float.intBitsToFloat(e[HEAD + e[1] * 4 + 1]) > 0.0F;
    }

    /** The configured feature kinds placed and met, for the status. */
    public String kindStatus() {
        StringBuilder b = new StringBuilder();
        for (int k = 1; k < KIND_NAMES.length; k++) {
            if (met[k] > 0) {
                b.append(b.length() == 0 ? "" : ", ").append(KIND_NAMES[k]).append(' ').append(placed[k]).append('/').append(met[k]);
            }
        }
        return b.toString();
    }
}
