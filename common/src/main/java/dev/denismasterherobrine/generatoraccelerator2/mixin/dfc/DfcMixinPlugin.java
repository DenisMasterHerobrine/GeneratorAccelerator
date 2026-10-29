package dev.denismasterherobrine.generatoraccelerator2.mixin.dfc;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.chunkload.ChunkLoadConfig;
import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.Coremods;
import dev.denismasterherobrine.generatoraccelerator2.compat.BeardifierChain;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.LateInspection;
import dev.denismasterherobrine.generatoraccelerator2.compat.LateOverwrites;
import dev.denismasterherobrine.generatoraccelerator2.compat.MergedMethods;
import dev.denismasterherobrine.generatoraccelerator2.compat.MixinTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.Takeover;
import dev.denismasterherobrine.generatoraccelerator2.compat.bettercaves.BetterCavesMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.yungscavebiomes.YungsCaveBiomesMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.zeta.ZetaMixins;
import dev.denismasterherobrine.generatoraccelerator2.dfc.Compat;
import dev.denismasterherobrine.generatoraccelerator2.features.predicate.PredicatePrograms;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import dev.denismasterherobrine.generatoraccelerator2.platform.Platform;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Skips NoiseChunk / climate hooks when a known mod already patches that code, and records which
 * vanilla classes and methods other mods' mixins change so the aquifer, beardifier and surface
 * level fast paths can stand down.
 */
public final class DfcMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = Ga2Log.get("Mixin");
    private static final String OWN_MIXIN_PACKAGE = "dev.denismasterherobrine.generatoraccelerator2.";
    private static final List<String> SURFACE_LEVEL_METHODS = List.of("computePreliminarySurfaceLevel(J)I", "preliminarySurfaceLevel(II)I");

    /**
     * A hook and the vanilla methods it takes over. The methods are inspected for other mods' changes once every mixin is
     * applied. Several entries may share a name. Their findings add up.
     *
     * @param passThrough methods called by the hooked methods that the fast path still calls through the class itself.
     *                    Another mod's change to them stays in effect and does not make the hook stand down.
     * @param mode        how the fast path replaces the methods ({@link MergedMethods.Mode})
     * @param boundary    in {@link MergedMethods.Mode#REPLACE}, which of GA2's handlers in the methods takes them over (see
     *                    {@link MergedMethods#foreignCalls}). Null selects the first handler.
     */
    private record FeatureHook(String name, List<String> methods, List<String> passThrough, MergedMethods.Mode mode, List<String> wrapTypes, String boundary) {
        FeatureHook(String name, List<String> methods) {
            this(name, methods, List.of(), MergedMethods.Mode.REPLACE, List.of(), null);
        }

        FeatureHook(String name, List<String> methods, List<String> passThrough) {
            this(name, methods, passThrough, MergedMethods.Mode.REPLACE, List.of(), null);
        }

        FeatureHook(String name, List<String> methods, MergedMethods.Mode mode) {
            this(name, methods, List.of(), mode, List.of(), null);
        }

        FeatureHook(String name, List<String> methods, List<String> passThrough, MergedMethods.Mode mode, List<String> wrapTypes) {
            this(name, methods, passThrough, mode, wrapTypes, null);
        }

        /** {@link MergedMethods.Mode#REPLACE}, starting from the GA2 handler that {@code boundary} selects. */
        FeatureHook(String name, List<String> methods, String boundary) {
            this(name, methods, List.of(), MergedMethods.Mode.REPLACE, List.of(), boundary);
        }

        List<String> inspect(ClassNode target) {
            return MergedMethods.mixins(inspectCalls(target));
        }

        List<MergedMethods.Change> inspectCalls(ClassNode target) {
            return MergedMethods.foreignCalls(target, methods, passThrough, mode, wrapTypes, OWN_MIXIN_PACKAGE, boundary);
        }
    }

    /** The methods of a vanilla modifier class whose work the placement pipeline does. */
    private static final List<String> MODIFIER_METHODS = List.of("getPositions", "shouldPlace", "count");
    private static final String CHUNK_STATUS = "Lnet/minecraft/world/level/chunk/status/ChunkStatus;";
    private static final String FEATURE_CTX = "Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;";
    private static final String CHUNK_READ = "read(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/ai/village/poi/PoiManager;"
        + "Lnet/minecraft/world/level/chunk/storage/RegionStorageInfo;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/world/level/chunk/ProtoChunk;";
    /** The methods whose work SculkFast does, by name, across the sculk classes. Each class has some of them. */
    private static final List<String> SCULK_METHODS = List.of("updateCursors", "addCursors", "addCursor", "clear", "update", "shouldUpdate", "mergeWith",
        "getBlockBehaviour", "getRandomizedNonCornerNeighbourOffsets", "getValidMovementPos", "isMovementUnobstructed", "isUnobstructed",
        "createWorldGenSpreader", "replaceableBlocks", "growthSpawnCost", "noGrowthRadius", "chargeDecayRate", "additionalDecayRate", "isWorldGeneration",
        "getPos", "getCharge", "getDecayDelay", "getFacingData", "attemptUseCharge", "canPlaceGrowth", "getDecayPenalty", "getRandomGrowthState",
        "canChangeBlockStateOnSpread", "regrow", "onDischarged", "attemptPlaceSculk", "hasSubstrateAccess", "getSpreader", "getSameSpaceSpreader",
        "stateCanBeReplaced", "getSpreadTypes", "isOtherBlockValidAsSource", "spreadAll", "spreadFromFaceTowardAllDirections", "spreadFromFaceTowardDirection",
        "getSpreadFromFaceTowardDirection", "spreadToFace", "canSpreadInto", "hasFace", "canSpreadFrom", "placeBlock", "getSpreadPos", "attemptSpreadVein",
        "updateDecayDelay", "getSculkSpreadDelay", "isValidStateForPlacement", "availableFaces", "hasAnyFace", "isFaceSupported", "isWaterloggable",
        "getFaceProperty", "levelEvent", "playSound", "getEntities",
        "getStateForPlacement(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Lnet/minecraft/world/level/block/state/BlockState;");
    /** Keyed by mixin simple name. */
    private static final Map<String, FeatureHook> FEATURE_HOOKS = Map.ofEntries(
        Map.entry("WorldGenRegionMixin", new FeatureHook(FeatureTargets.REGION, List.of(
            "getChunk(II" + CHUNK_STATUS + "Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            "<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/util/StaticCache2D;Lnet/minecraft/world/level/chunk/status/ChunkStep;Lnet/minecraft/world/level/chunk/ChunkAccess;)V"))),
        // the reads skip getChunk(int, int) and ProtoChunk's reads. They still look chunks up through getChunk(int, int, ChunkStatus, boolean).
        Map.entry("WorldGenRegionReadsMixin", new FeatureHook(FeatureTargets.REGION_READS, List.of(
            "getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
            "getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;",
            "getChunk(II)Lnet/minecraft/world/level/chunk/ChunkAccess;"),
            List.of("getChunk(II" + CHUNK_STATUS + "Z)Lnet/minecraft/world/level/chunk/ChunkAccess;"))),
        Map.entry("ProtoChunkReadsInspectMixin", new FeatureHook(FeatureTargets.REGION_READS, List.of(
            "getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
            "getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;"), MergedMethods.Mode.BYPASS)),
        Map.entry("CountOnEveryLayerInspectMixin", new FeatureHook(FeatureTargets.PLACEMENT_LAYERS, List.of("getPositions", "findOnGroundYPosition", "isEmpty"),
            MergedMethods.Mode.BYPASS)),
        Map.entry("PlacedFeatureMixin", new FeatureHook(FeatureTargets.PLACEMENT, List.of(
            "placeWithContext(Lnet/minecraft/world/level/levelgen/placement/PlacementContext;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z"))),
        // the predicate programs answer BlockStateBase.is(HolderSet) for fixed block lists themselves (by block identity)
        Map.entry("BlockStateIsInspectMixin", new FeatureHook(PredicatePrograms.STATE_IS,
            List.of("is(Lnet/minecraft/core/HolderSet;)Z", "contains(Lnet/minecraft/core/Holder;)Z"), MergedMethods.Mode.BYPASS)),
        // the predicate programs read the region directly, skipping getBlockState and whatever any mod put into it
        Map.entry("WorldGenRegionReadsStrictInspectMixin", new FeatureHook(FeatureTargets.REGION_READS_UNCHANGED,
            List.of("getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"), MergedMethods.Mode.BYPASS)),
        Map.entry("BlockPredicateFilterPredicateMixin", new FeatureHook(FeatureTargets.PREDICATE_CALLS,
            List.of("shouldPlace"), List.of(), MergedMethods.Mode.WRAP, List.of("BlockPredicate"))),
        Map.entry("EnvironmentScanPredicateMixin", new FeatureHook(FeatureTargets.PREDICATE_CALLS,
            List.of("getPositions"), List.of(), MergedMethods.Mode.WRAP, List.of("BlockPredicate"))),
        Map.entry("PlacementFilterInvoker", new FeatureHook(FeatureTargets.PLACEMENT_FILTERS, List.of(
            "getPositions(Lnet/minecraft/world/level/levelgen/placement/PlacementContext;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Ljava/util/stream/Stream;"), MergedMethods.Mode.BYPASS)),
        Map.entry("RepeatingPlacementInvoker", new FeatureHook(FeatureTargets.PLACEMENT_REPEATS, List.of(
            "getPositions(Lnet/minecraft/world/level/levelgen/placement/PlacementContext;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Ljava/util/stream/Stream;"), MergedMethods.Mode.BYPASS)),
        // the hook of each fast path lists every method of the feature whose work it does. Their lambdas are read with them.
        Map.entry("OreFeatureMixin", new FeatureHook("ore", List.of("place(" + FEATURE_CTX + ")Z", "doPlace", "canPlaceOre", "shouldSkipAirCheck"))),
        Map.entry("PalettedContainerMixin", new FeatureHook(FeatureTargets.DECORATION, List.of("getAll(Ljava/util/function/Consumer;)V"))),
        Map.entry("GeodeFeatureMixin", new FeatureHook("geode", List.of("place(" + FEATURE_CTX + ")Z"))),
        Map.entry("DiskFeatureMixin", new FeatureHook("disk", List.of("place(" + FEATURE_CTX + ")Z", "placeColumn"))),
        Map.entry("SpringFeatureMixin", new FeatureHook("spring", List.of("place(" + FEATURE_CTX + ")Z"))),
        Map.entry("SnowAndFreezeFeatureMixin", new FeatureHook("snowAndFreeze", List.of("place(" + FEATURE_CTX + ")Z"))),
        Map.entry("SeagrassFeatureMixin", new FeatureHook("seagrass", List.of("place(" + FEATURE_CTX + ")Z"))),
        Map.entry("KelpFeatureMixin", new FeatureHook("kelp", List.of("place(" + FEATURE_CTX + ")Z"))),
        Map.entry("MultifaceGrowthFeatureMixin", new FeatureHook("multiface", List.of("place(" + FEATURE_CTX + ")Z", "placeGrowthIfPossible", "isAirOrWater"))),
        Map.entry("MonsterRoomFeatureMixin", new FeatureHook("monsterRoom", List.of("place(" + FEATURE_CTX + ")Z"))),
        Map.entry("SimpleBlockFeatureMixin", new FeatureHook("simpleBlock", List.of("place(" + FEATURE_CTX + ")Z"))),
        Map.entry("SculkPatchFeatureMixin", new FeatureHook("sculk", List.of("place(" + FEATURE_CTX + ")Z", "canSpreadFrom"))),
        // the leaf rules and the shape update the tree passes answer themselves (features.tree.LeafUpdates)
        Map.entry("TreeLeavesInspectMixin", new FeatureHook("treeLeaves", List.of("updateShape", "getDistanceAt", "getOptionalDistanceAt"),
            MergedMethods.Mode.BYPASS)),
        // the set each decoration step collects its feature indexes into (features.StepFeatureSet)
        Map.entry("DecorationStepSetMixin", new FeatureHook("decorationSteps", List.of(
            "applyBiomeDecoration(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/StructureManager;)V"),
            List.of(), MergedMethods.Mode.WRAP, List.of("IntArraySet"))),
        // the beehive decorator the tree transcription places itself, with the leaves packed (features.tree.TreeBees)
        Map.entry("TreeBeesInspectMixin", new FeatureHook("treeBees", List.of("place"), MergedMethods.Mode.BYPASS)),
        // the entry the commonest features skip their context at (features.fast.RawPlace)
        Map.entry("FeatureSharedStateMixin", new FeatureHook("featureEntry", List.of("place(Lnet/minecraft/world/level/levelgen/feature/configurations/FeatureConfiguration;"
            + "Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z"),
            MergedMethods.Mode.BYPASS)),
        // the direction lists the multiface fast path draws itself (features.fast.SmallFeatures.multiface)
        Map.entry("MultifaceDirectionsInspectMixin", new FeatureHook("multifaceDirections", List.of("getShuffledDirections", "getShuffledDirectionsExcept"),
            MergedMethods.Mode.BYPASS)),
        // the biome climate methods the snow and freeze fast path skips for warm biomes (features.fast.WarmBiomes)
        Map.entry("ClimateInspectMixin", new FeatureHook("climate", List.of("shouldFreeze", "shouldSnow", "getTemperature", "getHeightAdjustedTemperature",
            "warmEnoughToRain", "coldEnoughToSnow", "getBaseTemperature"), MergedMethods.Mode.BYPASS)),
        // the sculk spreader, behaviours and vein spreaders SculkFast transcribes, and the region's no-op calls it skips
        Map.entry("SculkClassesInspectMixin", new FeatureHook("sculk", SCULK_METHODS, MergedMethods.Mode.BYPASS)),
        Map.entry("SculkInterfacesInspectMixin", new FeatureHook("sculk", SCULK_METHODS, MergedMethods.Mode.BYPASS)),
        Map.entry("TreeEdgeInspectMixin", new FeatureHook("trees", List.of("updateShapeAtEdge(Lnet/minecraft/world/level/LevelAccessor;ILnet/minecraft/world/phys/shapes/DiscreteVoxelShape;III)V",
            "forAllFaces", "forAllAxisFaces"), MergedMethods.Mode.BYPASS)),
        Map.entry("TreeFeatureMixin", new FeatureHook("trees", List.of("place(" + FEATURE_CTX + ")Z", "doPlace", "getMaxFreeTreeHeight", "updateLeaves",
            "isVine", "isAirOrLeaves", "setBlockKnownShape", "validTreePos", "setBlock"))),
        // the Feature helpers whose work the fast paths do. Every fast path stands down for a change to them (FeaturesCompat).
        Map.entry("FeatureHelpersInspectMixin", new FeatureHook(FeatureTargets.FEATURE_HELPERS, List.of("safeSetBlock", "markAboveForPostProcessing",
            "isAdjacentToAir", "checkNeighbors"), MergedMethods.Mode.BYPASS)),
        Map.entry("ChunkGeneratorDecorationMixin", new FeatureHook(FeatureTargets.DECORATION, List.of(
            "applyBiomeDecoration(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/StructureManager;)V"))));

    /** The block search {@code findBlockLightSources} runs. NeoForge's search, with its dynamic light emission test, or vanilla's on Fabric. */
    private static final String LIGHT_SOURCE_SEARCH = Platform.isNeoForge()
        ? "findBlocks(Ljava/util/function/Predicate;Ljava/util/function/BiPredicate;Ljava/util/function/BiConsumer;)V"
        : "findBlocks(Ljava/util/function/Predicate;Ljava/util/function/BiConsumer;)V";

    /** Scheduling, surface and carver hooks, keyed by mixin simple name. Recorded in {@link HookTargets}. */
    private static final String TEMPLATE_PLACE = "placeInWorld(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;"
        + "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructurePlaceSettings;Lnet/minecraft/util/RandomSource;I)Z";
    private static final String TEMPLATE_PROCESS = "processBlockInfos(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;"
        + "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructurePlaceSettings;Ljava/util/List;";
    /**
     * The template clip wraps one call in placeInWorld, so only changes to that call count there. It bypasses processBlockInfos and
     * the position transform, so any change to those counts.
     */
    private static final List<FeatureHook> TEMPLATE_CLIP_HOOKS = List.of(
        new FeatureHook(HookTargets.STRUCTURE_TEMPLATE_CLIP, List.of(TEMPLATE_PLACE), List.of(), MergedMethods.Mode.WRAP, List.of("processBlockInfos")),
        new FeatureHook(HookTargets.STRUCTURE_TEMPLATE_CLIP, List.of(TEMPLATE_PROCESS + ")Ljava/util/List;",
            TEMPLATE_PROCESS + "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate;)Ljava/util/List;",
            "calculateRelativePosition(Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructurePlaceSettings;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/BlockPos;",
            "transform(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Mirror;Lnet/minecraft/world/level/block/Rotation;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/BlockPos;"),
            MergedMethods.Mode.BYPASS));
    private static final Map<String, List<FeatureHook>> STEP_HOOKS = Map.ofEntries(
        // Zeta's cluster walk (compat/zeta). GA2's callback takes the loop over. What the loop does in place or relies on
        // counts wherever changed.
        Map.entry("ClusterBasedGeneratorMixin", List.of(
            new FeatureHook(HookTargets.ZETA_CLUSTERS, List.of("generateChunkPart"),
                List.of("around", "createContext", "getLowerBound", "getUpperBound", "src", "radius", "isInside", "canPlaceAt", "consume")),
            new FeatureHook(HookTargets.ZETA_CLUSTERS, List.of(ZetaMixins.LAMBDA), MergedMethods.Mode.BYPASS))),
        Map.entry("ZetaClustersInspectMixin", List.of(
            new FeatureHook(HookTargets.ZETA_CLUSTERS, List.of("forEachChunkBlock", "isInside"), MergedMethods.Mode.BYPASS))),
        Map.entry("StoneClustersInspectMixin", List.of(
            new FeatureHook(HookTargets.ZETA_CLUSTERS, List.of("canPlaceBlock", "canPlaceAt"), MergedMethods.Mode.BYPASS))),
        // Supplementaries' barnacle search (compat/supplementaries). GA2's callback takes place over. What it
        // calls in its class and the block's placement test count wherever changed.
        Map.entry("BarnaclesFeatureMixin", List.of(
            new FeatureHook(HookTargets.SUPPLEMENTARIES_BARNACLES, List.of("place")))),
        Map.entry("BarnaclesBlockInspectMixin", List.of(
            new FeatureHook(HookTargets.SUPPLEMENTARIES_BARNACLES, List.of("isValidStateForPlacement", "canBeAttachTo"), MergedMethods.Mode.BYPASS))),
        // the template loads (structure.TemplateLoads). GA2 wraps the repository's computeIfAbsent in get. Any other change there counts.
        Map.entry("StructureTemplateLoadsMixin", List.of(
            new FeatureHook(HookTargets.STRUCTURE_TEMPLATE_LOADS, List.of("get(Lnet/minecraft/resources/ResourceLocation;)Ljava/util/Optional;"),
                List.of(), MergedMethods.Mode.WRAP, List.of()))),
        // the POI skip (state.PoiStates). GA2 wraps the call in setBlock. The call and the lookup it relies on count wherever
        // changed.
        Map.entry("PoiSkipMixin", List.of(
            new FeatureHook(HookTargets.STATE_POI_SKIP, List.of("setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z"),
                List.of(), MergedMethods.Mode.WRAP, List.of("Lnet/minecraft/server/level/ServerLevel;")))),
        Map.entry("PoiInspectMixin", List.of(
            new FeatureHook(HookTargets.STATE_POI_SKIP, List.of("onBlockStateChange", "forState"), MergedMethods.Mode.BYPASS))),
        // the still water skip (fluid.StillWater). GA2 wraps the fluid tick call in postProcessGeneration. The tick it leaves out
        // counts wherever changed.
        Map.entry("PostProcessFluidMixin", List.of(
            new FeatureHook(HookTargets.FLUID_POST_PROCESS, List.of("postProcessGeneration()V"),
                List.of(), MergedMethods.Mode.WRAP, List.of("Lnet/minecraft/world/level/material/FluidState;")))),
        Map.entry("FluidTickInspectMixin", List.of(
            new FeatureHook(HookTargets.FLUID_POST_PROCESS, List.of("tick", "spread", "spreadToSides", "getSpread", "getNewLiquid", "canSpreadTo",
                "canPassThrough", "canPassThroughWall", "canHoldFluid", "isSourceBlockOfThisType", "canBeReplacedWith", "sourceNeighborCount",
                "getDropOff", "isSame"), MergedMethods.Mode.BYPASS))),
        // the hidden water skip leaves out the renderer's whole method. Any change to the method or its face checks counts, and so
        // does another mod's redirect or wrapper of the call in renderLiquid.
        Map.entry("LiquidBlockRendererMixin", List.of(
            new FeatureHook(HookTargets.FLUID_HIDDEN_WATER, List.of("tesselate", "shouldRenderFace", "isNeighborSameFluid", "isNeighborStateHidingOverlay"),
                MergedMethods.Mode.BYPASS))),
        Map.entry("LiquidRenderCallMixin", List.of(
            new FeatureHook(HookTargets.FLUID_HIDDEN_WATER, List.of("renderLiquid"), List.of(), MergedMethods.Mode.WRAP,
                List.of("Lnet/minecraft/client/renderer/block/LiquidBlockRenderer;")))),
        Map.entry("CarvingContextAccessor", List.of(
            new FeatureHook(HookTargets.CARVER_TOP_MATERIAL, List.of(
                "topMaterial(Ljava/util/function/Function;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/core/BlockPos;Z)Ljava/util/Optional;")))),
        Map.entry("SurfaceSystemMixin", List.of(
            new FeatureHook(HookTargets.CARVER_TOP_MATERIAL, List.of(
                "topMaterial(Lnet/minecraft/world/level/levelgen/SurfaceRules$RuleSource;Lnet/minecraft/world/level/levelgen/carver/CarvingContext;"
                    + "Ljava/util/function/Function;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/NoiseChunk;"
                    + "Lnet/minecraft/core/BlockPos;Z)Ljava/util/Optional;")),
            new FeatureHook(HookTargets.SURFACE_BUILD, List.of(
                "buildSurface(Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/biome/BiomeManager;Lnet/minecraft/core/Registry;Z"
                    + "Lnet/minecraft/world/level/levelgen/WorldGenerationContext;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/NoiseChunk;"
                    + "Lnet/minecraft/world/level/levelgen/SurfaceRules$RuleSource;)V",
                "getSurfaceDepth(II)I")),
            // the GPU's surface stands in for the whole step. Any change to the build or to the noises it reads keeps the GPU
            // surface off, even a change that runs before GA2's own hook. For example, Pufferfish's Biome Dither swaps the biome
            // manager at the head, and GA2's CPU builder then uses that biome manager.
            new FeatureHook(HookTargets.GPU_SURFACE, List.of(
                "buildSurface(Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/biome/BiomeManager;Lnet/minecraft/core/Registry;Z"
                    + "Lnet/minecraft/world/level/levelgen/WorldGenerationContext;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/NoiseChunk;"
                    + "Lnet/minecraft/world/level/levelgen/SurfaceRules$RuleSource;)V",
                "getSurfaceDepth(II)I", "getSurfaceSecondary(II)D", "getBand(III)Lnet/minecraft/world/level/block/state/BlockState;"), MergedMethods.Mode.BYPASS),
            new FeatureHook(HookTargets.SURFACE_TOP_MATERIAL, List.of(
                "topMaterial(Lnet/minecraft/world/level/levelgen/SurfaceRules$RuleSource;Lnet/minecraft/world/level/levelgen/carver/CarvingContext;"
                    + "Ljava/util/function/Function;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/NoiseChunk;"
                    + "Lnet/minecraft/core/BlockPos;Z)Ljava/util/Optional;",
                "getSurfaceDepth(II)I")))),
        Map.entry("BiomeManagerAccessor", List.of(
            new FeatureHook(HookTargets.SURFACE_BIOME_ZOOM, List.of(
                "getBiome(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Holder;",
                "getFiddledDistance(JIIIDDD)D",
                "getFiddle(J)D")))),
        // The fill loop stands down for changes to the loop itself. A change to the per-block state (getInterpolatedState
        // and what it runs, which FastFill does in place) only makes FastFill ask the NoiseChunk through its real method.
        // The constructor is not inspected. FastFill checks the material rules and the final density cache that the
        // constructor built, on every chunk.
        Map.entry("FastFillMixin", List.of(
            new FeatureHook(HookTargets.NOISE_FILL, List.of(
                "doFill(Lnet/minecraft/world/level/levelgen/blending/Blender;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/chunk/ChunkAccess;II)Lnet/minecraft/world/level/chunk/ChunkAccess;")),
            new FeatureHook(HookTargets.NOISE_FILL, List.of(
                "debugPreliminarySurfaceLevel(Lnet/minecraft/world/level/levelgen/NoiseChunk;IIILnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;"),
                MergedMethods.Mode.BYPASS))),
        // GPU world generation answers the noise and biome steps at their head (gpu.GpuSteps). The biome step's work is done on the GPU.
        Map.entry("GpuGeneratorMixin", List.of(
            new FeatureHook(HookTargets.GPU_NOISE, List.of(
                "fillFromNoise(Lnet/minecraft/world/level/levelgen/blending/Blender;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkAccess;)Ljava/util/concurrent/CompletableFuture;")),
            new FeatureHook(HookTargets.GPU_BIOMES, List.of(
                "createBiomes(Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/levelgen/blending/Blender;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkAccess;)Ljava/util/concurrent/CompletableFuture;")),
            new FeatureHook(HookTargets.GPU_BIOMES, List.of(
                "doCreateBiomes(Lnet/minecraft/world/level/levelgen/blending/Blender;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkAccess;)V"),
                MergedMethods.Mode.BYPASS))),
        // a chunk the GPU filled goes without a NoiseChunk. The calls that would make or read a NoiseChunk are wrapped.
        Map.entry("GpuSurfaceMixin", List.of(
            new FeatureHook(HookTargets.GPU_TERRAIN, List.of(
                "buildSurface(Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/WorldGenerationContext;Lnet/minecraft/world/level/levelgen/RandomState;"
                    + "Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/biome/BiomeManager;Lnet/minecraft/core/Registry;"
                    + "Lnet/minecraft/world/level/levelgen/blending/Blender;)V"),
                List.of(), MergedMethods.Mode.WRAP, List.of("Lnet/minecraft/world/level/levelgen/NoiseChunk;")),
            new FeatureHook(HookTargets.GPU_SURFACE, List.of(
                "buildSurface(Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/WorldGenerationContext;Lnet/minecraft/world/level/levelgen/RandomState;"
                    + "Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/biome/BiomeManager;Lnet/minecraft/core/Registry;"
                    + "Lnet/minecraft/world/level/levelgen/blending/Blender;)V")))),
        Map.entry("HolderTagsMixin", List.of(
            new FeatureHook(HookTargets.TAG_BITS, List.of("is(Lnet/minecraft/tags/TagKey;)Z", "bindTags(Ljava/util/Collection;)V"), MergedMethods.Mode.BYPASS),
            // vanilla has no equals or hashCode here. NeoForge adds both. A mod that adds or changes them counts as a change
            new FeatureHook(HookTargets.DIRECT_SETS, List.of("equals(Ljava/lang/Object;)Z", "hashCode()I", "key()Lnet/minecraft/resources/ResourceKey;",
                "kind()Lnet/minecraft/core/Holder$Kind;", "getKey()Lnet/minecraft/resources/ResourceKey;"), MergedMethods.Mode.BYPASS))),
        Map.entry("DirectSetMixin", List.of(
            new FeatureHook(HookTargets.DIRECT_SETS, List.of("contains(Lnet/minecraft/core/Holder;)Z"), MergedMethods.Mode.BYPASS))),
        // the height skips getChunk(int, int). It still looks chunks up through getChunk(int, int, ChunkStatus, boolean).
        // the random patch's repeated tries (features.placement.PatchRepeats). The skip relies on the loop changing no block between
        // tries, so any change to the method counts
        Map.entry("RandomPatchRepeatsMixin", List.of(
            new FeatureHook(HookTargets.PATCH_REPEATS, List.of("place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z"), MergedMethods.Mode.BYPASS))),
        Map.entry("WorldGenRegionHeightsMixin", List.of(
            new FeatureHook(HookTargets.REGION_HEIGHTS, List.of("getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I",
                "getChunk(II)Lnet/minecraft/world/level/chunk/ChunkAccess;"), MergedMethods.Mode.BYPASS))),
        Map.entry("SectionWriteMixin", List.of(
            new FeatureHook(HookTargets.SECTION_WRITE, List.of("setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;"),
                MergedMethods.Mode.BYPASS),
            new FeatureHook(HookTargets.SECTION_WRITE_UNLOCKED, List.of("setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;"),
                MergedMethods.Mode.BYPASS))),
        Map.entry("PaletteCopyMixin", List.of(
            new FeatureHook(HookTargets.PALETTE_COPY, List.of("copyFrom(Lnet/minecraft/world/level/chunk/Palette;Lnet/minecraft/util/BitStorage;)V"), MergedMethods.Mode.BYPASS))),
        Map.entry("LinearPaletteMixin", List.of(
            new FeatureHook(HookTargets.PALETTE, List.of("idFor(Ljava/lang/Object;)I", "read(Lnet/minecraft/network/FriendlyByteBuf;)V"), MergedMethods.Mode.BYPASS))),
        Map.entry("HashMapPaletteMixin", List.of(
            new FeatureHook(HookTargets.PALETTE, List.of("idFor(Ljava/lang/Object;)I", "read(Lnet/minecraft/network/FriendlyByteBuf;)V"), MergedMethods.Mode.BYPASS))),
        Map.entry("FullBlockShapeMixin", List.of(
            new FeatureHook(HookTargets.FULL_BLOCK_SHAPE, List.of("isShapeFullBlock(Lnet/minecraft/world/phys/shapes/VoxelShape;)Z"), MergedMethods.Mode.BYPASS))),
        Map.entry("FluidStateEmptyMixin", List.of(
            new FeatureHook(HookTargets.FLUID_EMPTY, List.of("isEmpty()Z"), MergedMethods.Mode.BYPASS))),
        Map.entry("ChunkHeightsMixin", List.of(
            new FeatureHook(HookTargets.CHUNK_HEIGHTS, List.of("getMinBuildHeight()I", "getHeight()I"), MergedMethods.Mode.BYPASS))),
        // the GPU's surface computes biome temperatures itself. Any change to them keeps the surface on the CPU.
        Map.entry("BiomeTemperatureAccessor", List.of(
            new FeatureHook(HookTargets.GPU_SURFACE, List.of("getTemperature(Lnet/minecraft/core/BlockPos;)F", "getHeightAdjustedTemperature(Lnet/minecraft/core/BlockPos;)F",
                "coldEnoughToSnow(Lnet/minecraft/core/BlockPos;)Z", "warmEnoughToRain(Lnet/minecraft/core/BlockPos;)Z"), MergedMethods.Mode.BYPASS))),
        Map.entry("GpuSurfaceContextMixin", List.of(
            new FeatureHook(HookTargets.GPU_TERRAIN, List.of("getMinSurfaceLevel()I"), List.of(), MergedMethods.Mode.WRAP,
                List.of("Lnet/minecraft/world/level/levelgen/NoiseChunk;")))),
        Map.entry("GpuBiomeSourceInspectMixin", List.of(
            new FeatureHook(HookTargets.GPU_BIOMES, List.of(
                "getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;",
                "getNoiseBiome(Lnet/minecraft/world/level/biome/Climate$TargetPoint;)Lnet/minecraft/core/Holder;",
                "parameters()Lnet/minecraft/world/level/biome/Climate$ParameterList;"),
                MergedMethods.Mode.BYPASS),
            // the answers kept for points outside a region come from this lookup. Only reviewed changes keep it position-only
            new FeatureHook(HookTargets.BIOME_ANSWERS, List.of(
                "getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;",
                "getNoiseBiome(Lnet/minecraft/world/level/biome/Climate$TargetPoint;)Lnet/minecraft/core/Holder;",
                "parameters()Lnet/minecraft/world/level/biome/Climate$ParameterList;"),
                MergedMethods.Mode.BYPASS))),
        Map.entry("WorldGenRegionBiomesMixin", List.of(
            new FeatureHook(HookTargets.BIOME_ANSWERS, List.of("getUncachedNoiseBiome(III)Lnet/minecraft/core/Holder;"), MergedMethods.Mode.BYPASS))),
        Map.entry("ServerLevelBiomeAnswersMixin", List.of(
            new FeatureHook(HookTargets.BIOME_ANSWERS, List.of("getUncachedNoiseBiome(III)Lnet/minecraft/core/Holder;"), MergedMethods.Mode.BYPASS))),
        Map.entry("GpuClimateSamplerInspectMixin", List.of(
            new FeatureHook(HookTargets.GPU_BIOMES, List.of("sample(III)Lnet/minecraft/world/level/biome/Climate$TargetPoint;"), MergedMethods.Mode.BYPASS),
            new FeatureHook(HookTargets.GPU_CLIMATE_POINTS, List.of("sample(III)Lnet/minecraft/world/level/biome/Climate$TargetPoint;"), MergedMethods.Mode.BYPASS))),
        Map.entry("NoiseChunkInspectMixin", List.of(
            new FeatureHook(HookTargets.NOISE_FILL, List.of("updateForZ(ID)V"), MergedMethods.Mode.BYPASS),
            new FeatureHook(HookTargets.NOISE_FILL_STEPS, List.of("updateForX(ID)V", "updateForY(ID)V"), MergedMethods.Mode.BYPASS),
            new FeatureHook(HookTargets.NOISE_FILL_STATE, List.of(
                "getInterpolatedState()Lnet/minecraft/world/level/block/state/BlockState;",
                "lambda$new$0(Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)Lnet/minecraft/world/level/block/state/BlockState;"),
                MergedMethods.Mode.BYPASS))),
        Map.entry("MaterialRuleListMixin", List.of(
            new FeatureHook(HookTargets.NOISE_FILL_STATE, List.of("calculate(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)Lnet/minecraft/world/level/block/state/BlockState;"),
                MergedMethods.Mode.BYPASS),
            new FeatureHook(HookTargets.NOISE_MATERIAL_RULES, List.of("calculate(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)Lnet/minecraft/world/level/block/state/BlockState;")))),
        Map.entry("LevelChunkSectionInspectMixin", List.of(
            new FeatureHook(HookTargets.NOISE_SECTION_WRITES, List.of(
                "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;"),
                MergedMethods.Mode.BYPASS))),
        // the jigsaw block lists of a template palette, cached per rotation (StructureTemplateMixin)
        Map.entry("StructureTemplateMixin", List.of(
            new FeatureHook(HookTargets.STRUCTURE_JIGSAW_BLOCKS, List.of(
                "filterBlocks(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructurePlaceSettings;"
                    + "Lnet/minecraft/world/level/block/Block;Z)Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"),
                MergedMethods.Mode.BYPASS))),
        // placeInWorld's processBlockInfos call, clipped to the bounding box (structure.TemplatePlacement). One mixin per loader:
        // NeoForge's call has a sixth argument. A listed method the running loader does not have is never matched.
        Map.entry("StructureTemplatePlaceMixin", TEMPLATE_CLIP_HOOKS),
        Map.entry("StructureTemplatePlaceNeoMixin", TEMPLATE_CLIP_HOOKS),
        Map.entry("CacheAllInCellInspectMixin", List.of(
            new FeatureHook(HookTargets.NOISE_FILL_STATE, List.of("compute(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D"), MergedMethods.Mode.BYPASS))),
        // a change to the vein rule turns off the whole-cell short cut and the rule's transcription (dfc.runtime.VeinRule).
        // Vanilla's lambda then runs per block.
        Map.entry("OreVeinifierInspectMixin", List.of(
            new FeatureHook(HookTargets.NOISE_VEIN_RULE, List.of(
                "create(Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction;"
                    + "Lnet/minecraft/world/level/levelgen/PositionalRandomFactory;)Lnet/minecraft/world/level/levelgen/NoiseChunk$BlockStateFiller;",
                "lambda$create$0(Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/levelgen/PositionalRandomFactory;"
                    + "Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)Lnet/minecraft/world/level/block/state/BlockState;"),
                MergedMethods.Mode.BYPASS))),
        // Biolith's built parameter list returned at the head of parameters() (compat/biolith)
        Map.entry("MultiNoiseParametersMixin", List.of(
            new FeatureHook(HookTargets.BIOME_PARAMETERS, List.of("parameters()Lnet/minecraft/world/level/biome/Climate$ParameterList;")))),
        Map.entry("ClimateParameterListMixin", List.of(
            new FeatureHook(HookTargets.BIOME_CLIMATE_SEARCH, List.of("findValueIndex(Lnet/minecraft/world/level/biome/Climate$TargetPoint;)Ljava/lang/Object;",
                "findValueIndex(Lnet/minecraft/world/level/biome/Climate$TargetPoint;Lnet/minecraft/world/level/biome/Climate$DistanceMetric;)Ljava/lang/Object;")),
            // the GPU's biomes and the fused lookup (biome.FusedLookup) search the tree without the list's findValue
            new FeatureHook(HookTargets.GPU_BIOMES, List.of("findValue(Lnet/minecraft/world/level/biome/Climate$TargetPoint;)Ljava/lang/Object;"),
                MergedMethods.Mode.BYPASS))),
        Map.entry("NoiseBasedChunkGeneratorMixin", List.of(
            // the parallel surface step calls this entry point itself and only hands in the blender (SurfaceBlenderMixin).
            // Callbacks keep working, such as Lithostitched filling its bands at the head. A wrapper around the blender or
            // the inner call keeps the step inline.
            new FeatureHook(HookTargets.SURFACE_ENTRY, List.of(
                "buildSurface(Lnet/minecraft/server/level/WorldGenRegion;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/chunk/ChunkAccess;)V"),
                List.of(), MergedMethods.Mode.WRAP, List.of("Lnet/minecraft/world/level/levelgen/blending/Blender;")),
            new FeatureHook(HookTargets.CARVER_ENTRY, List.of(
                "applyCarvers(Lnet/minecraft/server/level/WorldGenRegion;JLnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/biome/BiomeManager;"
                    + "Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/GenerationStep$Carving;)V")),
            // whole-cell air reads the vanilla picker as a function of y alone. A changed picker is asked per block instead.
            new FeatureHook(HookTargets.NOISE_FLUID_PICKER, List.of(
                "createFluidPicker(Lnet/minecraft/world/level/levelgen/NoiseGeneratorSettings;)Lnet/minecraft/world/level/levelgen/Aquifer$FluidPicker;",
                "lambda$createFluidPicker$4(ILnet/minecraft/world/level/levelgen/Aquifer$FluidStatus;Lnet/minecraft/world/level/levelgen/Aquifer$FluidStatus;III)Lnet/minecraft/world/level/levelgen/Aquifer$FluidStatus;"),
                MergedMethods.Mode.BYPASS))),
        // the fossil probe answers for vanilla's placement and biome check. A change to either turns the probe off.
        Map.entry("NetherFossilStructureMixin", List.of(
            new FeatureHook(HookTargets.STRUCTURE_FOSSIL_PROBE, List.of(
                "findGenerationPoint(Lnet/minecraft/world/level/levelgen/structure/Structure$GenerationContext;)Ljava/util/Optional;"), MergedMethods.Mode.BYPASS))),
        Map.entry("NoiseHeightProbeMixin", List.of(
            new FeatureHook(HookTargets.STRUCTURE_HEIGHT_PROBE, List.of(
                "getBaseHeight(IILnet/minecraft/world/level/levelgen/Heightmap$Types;Lnet/minecraft/world/level/LevelHeightAccessor;Lnet/minecraft/world/level/levelgen/RandomState;)I",
                "iterateNoiseColumn(Lnet/minecraft/world/level/LevelHeightAccessor;Lnet/minecraft/world/level/levelgen/RandomState;IILorg/apache/commons/lang3/mutable/MutableObject;Ljava/util/function/Predicate;)Ljava/util/OptionalInt;")))),
        Map.entry("StructureInvoker", List.of(
            new FeatureHook(HookTargets.STRUCTURE_FOSSIL_PROBE, List.of(
                "findValidGenerationPoint(Lnet/minecraft/world/level/levelgen/structure/Structure$GenerationContext;)Ljava/util/Optional;",
                "isValidBiome(Lnet/minecraft/world/level/levelgen/structure/Structure$GenerationStub;Lnet/minecraft/world/level/levelgen/structure/Structure$GenerationContext;)Z"),
                MergedMethods.Mode.BYPASS))),
        // the ring bound follows from how vanilla computes and stores the positions. A change to that computation or storage
        // keeps every check on the positions themselves. So does a wrapper of the positions inside the check, after GA2's callback.
        Map.entry("ChunkGeneratorStructureStateAccessor", List.of(
            new FeatureHook(HookTargets.STRUCTURE_RING_BOUND, List.of("generatePositions", "lambda$generatePositions$4", "generateRingPositions",
                "lambda$generateRingPositions$5", "getRingPositionsFor"), MergedMethods.Mode.BYPASS))),
        Map.entry("ConcentricRingsStructurePlacementMixin", List.of(
            new FeatureHook(HookTargets.STRUCTURE_RING_BOUND, List.of(
                "isPlacementChunk(Lnet/minecraft/world/level/chunk/ChunkGeneratorStructureState;II)Z")))),
        // generateSurface has two GA2 handlers. One takes the step over at its head, when GA2 runs every change other mods
        // make to generateSurface itself. Otherwise, the other handler hands the generator call to the pool.
        // The SURFACE and CARVERS steps hand only their generator call to the pool. Other mods' code before the call runs as
        // in vanilla (before GA2's wrapper). Code after the call would run before the call finishes, so the call stays inline.
        Map.entry("ChunkStatusTasksMixin", List.of(
            new FeatureHook(HookTargets.SURFACE_STEP, List.of("generateSurface"), "!ga2$surfaceStep"),
            new FeatureHook(HookTargets.SURFACE_STEP_BODY, List.of("generateSurface"), "ga2$surfaceStep"),
            new FeatureHook(HookTargets.CARVER_STEP, List.of("generateCarvers")))),
        // the join wait decides the spawn area loop through its count. Another mod that changes that loop (callbacks aside)
        // keeps its own wait.
        Map.entry("MinecraftServerJoinMixin", List.of(
            new FeatureHook(HookTargets.JOIN_SPAWN_WAIT, List.of("prepareLevels(Lnet/minecraft/server/level/progress/ChunkProgressListener;)V"),
                List.of(), MergedMethods.Mode.WRAP, List.of()))),
        Map.entry("CarverClassesInspectMixin", List.of(
            // everything the carver runner simulates or carves itself, by name (each class has some of them)
            new FeatureHook(HookTargets.CARVER_REPLAY, List.of("isStartChunk", "carve", "doCarve", "createRoom", "createTunnel", "getCaveBound", "getThickness",
                "getYScale", "shouldSkip", "initWidthFactors", "updateVerticalRadius", "carveEllipsoid", "carveBlock", "getCarveState", "canReplaceBlock",
                "canReach", "isDebugEnabled", "getDebugState"), MergedMethods.Mode.BYPASS))),
        Map.entry("PalettedContainerPackMixin", List.of(
            new FeatureHook(HookTargets.STORAGE_PACK, List.of(
                "pack(Lnet/minecraft/core/IdMap;Lnet/minecraft/world/level/chunk/PalettedContainer$Strategy;)Lnet/minecraft/world/level/chunk/PalettedContainerRO$PackedData;",
                "swapPalette([ILjava/util/function/IntUnaryOperator;)V"),
                // the fast path takes the container's lock itself (FerriteCore and Lithium replace the threading detector)
                List.of("acquire()V", "release()V")),
            // chunk packets' block data is written on GA2's packet threads. Another mod's change to the writes may expect the server thread.
            new FeatureHook(HookTargets.CHUNK_PACKET_PREP, List.of("write(Lnet/minecraft/network/FriendlyByteBuf;)V", "getSerializedSize()I"),
                MergedMethods.Mode.BYPASS))),
        Map.entry("DataLayerStorageMapMixin", List.of(
            new FeatureHook(HookTargets.LIGHT_ENGINE, List.of("getLayer(J)Lnet/minecraft/world/level/chunk/DataLayer;", "clearCache()V", "setLayer(JLnet/minecraft/world/level/chunk/DataLayer;)V",
                "copyDataLayer(J)Lnet/minecraft/world/level/chunk/DataLayer;", "removeLayer(J)Lnet/minecraft/world/level/chunk/DataLayer;")),
            // a snapshot holds what the working map holds. Another mod's change to how sections enter or leave it counts.
            new FeatureHook(HookTargets.LIGHT_PUBLISH, List.of("setLayer(JLnet/minecraft/world/level/chunk/DataLayer;)V",
                "copyDataLayer(J)Lnet/minecraft/world/level/chunk/DataLayer;", "removeLayer(J)Lnet/minecraft/world/level/chunk/DataLayer;"), MergedMethods.Mode.BYPASS))),
        // while recording, the callbacks take over the calls to both layers and the propagation
        Map.entry("LevelLightEngineMixin", List.of(
            new FeatureHook(HookTargets.LIGHT_LAYERS, List.of("checkBlock", "updateSectionStatus", "setLightEnabled", "propagateLightSources",
                "queueSectionData", "retainData", "runLightUpdates")))),
        // the recording lasts from the update's start to its propagation. Another mod's change between the two counts.
        Map.entry("ThreadedLightLayersMixin", List.of(
            new FeatureHook(HookTargets.LIGHT_LAYERS, List.of("runUpdate")))),
        // the queue takes over both methods. Other mods' code in the update itself (runUpdate) still runs.
        Map.entry("ThreadedLightTasksMixin", List.of(
            new FeatureHook(HookTargets.LIGHT_TASKS, List.of("addTask", "tryScheduleUpdate")))),
        Map.entry("SkyLayerMapCopyMixin", List.of(
            new FeatureHook(HookTargets.LIGHT_PUBLISH, List.of("copy"), List.of(), MergedMethods.Mode.WRAP, List.of()))),
        Map.entry("BlockLayerMapCopyMixin", List.of(
            new FeatureHook(HookTargets.LIGHT_PUBLISH, List.of("copy"), List.of(), MergedMethods.Mode.WRAP, List.of()))),
        Map.entry("LightEngineMixin", List.of(
            new FeatureHook(HookTargets.LIGHT_ENGINE, List.of("getChunk(II)Lnet/minecraft/world/level/chunk/LightChunk;", "clearChunkCache()V", "propagateIncreases()I",
                "getState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
                "shapeOccludes(JLnet/minecraft/world/level/block/state/BlockState;JLnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;)Z",
                "getOcclusionShape(Lnet/minecraft/world/level/block/state/BlockState;JLnet/minecraft/core/Direction;)Lnet/minecraft/world/phys/shapes/VoxelShape;")))),
        Map.entry("SkyLightEngineMixin", List.of(
            new FeatureHook(HookTargets.LIGHT_ENGINE, List.of("propagateIncrease(JJI)V")))),
        Map.entry("BlockLightEngineMixin", List.of(
            new FeatureHook(HookTargets.LIGHT_ENGINE, List.of("propagateIncrease(JJI)V")))),
        Map.entry("LayerLightSectionStorageMixin", List.of(
            new FeatureHook(HookTargets.LIGHT_ENGINE, List.of("setStoredLevel(JI)V", "swapSectionMap()V")),
            new FeatureHook(HookTargets.LIGHT_PUBLISH, List.of("swapSectionMap()V"), List.of(), MergedMethods.Mode.WRAP, List.of()),
            // the working map's sections change only here, each one recorded in changedSections
            new FeatureHook(HookTargets.LIGHT_PUBLISH, List.of("getDataLayerToWrite(J)Lnet/minecraft/world/level/chunk/DataLayer;", "setStoredLevel(JI)V",
                "markNewInconsistencies(Lnet/minecraft/world/level/lighting/LightEngine;)V", "initializeSection(J)V"), MergedMethods.Mode.BYPASS))),
        // parallellight (own mixin config). The propagation of a split batch runs the bodies of these methods itself.
        Map.entry("LightEngineParallelMixin", List.of(
            new FeatureHook(HookTargets.LIGHT_PARALLEL, List.of("runLightUpdates()I", "propagateIncreases()I", "propagateDecreases()I",
                "getChunk(II)Lnet/minecraft/world/level/chunk/LightChunk;",
                "getState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
                "getOpacity(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)I",
                "shapeOccludes(JLnet/minecraft/world/level/block/state/BlockState;JLnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;)Z",
                "getOcclusionShape(Lnet/minecraft/world/level/block/state/BlockState;JLnet/minecraft/core/Direction;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
                "enqueueIncrease(JJ)V", "enqueueDecrease(JJ)V"),
                // called through the engine as vanilla calls them
                List.of("checkNode(J)V", "clearChunkCache()V"), MergedMethods.Mode.REPLACE, List.of()))),
        Map.entry("SkyLightEngineInspectMixin", List.of(
            new FeatureHook(HookTargets.LIGHT_PARALLEL, List.of("propagateIncrease(JJI)V", "propagateDecrease(JJ)V",
                "propagateFromEmptySections(JLnet/minecraft/core/Direction;IZI)V", "countEmptySectionsBelowIfAtBorder(J)I"), MergedMethods.Mode.BYPASS))),
        Map.entry("BlockLightEngineInspectMixin", List.of(
            new FeatureHook(HookTargets.LIGHT_PARALLEL, List.of("propagateIncrease(JJI)V", "propagateDecrease(JJ)V",
                "getEmission(JLnet/minecraft/world/level/block/state/BlockState;)I"), MergedMethods.Mode.BYPASS))),
        Map.entry("LayerLightSectionStorageAccessor", List.of(
            new FeatureHook(HookTargets.LIGHT_PARALLEL, List.of("getStoredLevel(J)I", "setStoredLevel(JI)V",
                "getDataLayer(JZ)Lnet/minecraft/world/level/chunk/DataLayer;", "storingLightForSection(J)Z"), MergedMethods.Mode.BYPASS))),
        Map.entry("DataLayerStorageMapAccessor", List.of(
            new FeatureHook(HookTargets.LIGHT_PARALLEL, List.of("getLayer(J)Lnet/minecraft/world/level/chunk/DataLayer;",
                "copyDataLayer(J)Lnet/minecraft/world/level/chunk/DataLayer;"), MergedMethods.Mode.BYPASS))),
        Map.entry("ChunkAccessLightMixin", List.of(
            new FeatureHook(HookTargets.LIGHT_SOURCES, List.of("findBlockLightSources(Ljava/util/function/BiConsumer;)V", LIGHT_SOURCE_SEARCH)))),
        Map.entry("RegionFileStorageMixin", List.of(
            new FeatureHook(HookTargets.STORAGE_REGION_WRITE, List.of("write(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)V")),
            new FeatureHook(HookTargets.STORAGE_REGION_SYNC, List.of("getRegionFile(Lnet/minecraft/world/level/ChunkPos;)Lnet/minecraft/world/level/chunk/storage/RegionFile;")),
            // a parallel read does read's work itself (region file lookup, stream, parse) instead of calling it
            new FeatureHook(HookTargets.STORAGE_PARALLEL_READ, List.of("read(Lnet/minecraft/world/level/ChunkPos;)Lnet/minecraft/nbt/CompoundTag;"),
                List.of("getRegionFile(Lnet/minecraft/world/level/ChunkPos;)Lnet/minecraft/world/level/chunk/storage/RegionFile;"), MergedMethods.Mode.BYPASS, List.of()))),
        // the palette decodes, section constructor and tick loaders inside read are wrapped. The rest of read runs as vanilla's.
        Map.entry("ChunkSerializerReadMixin", List.of(
            new FeatureHook(HookTargets.STORAGE_LOAD_PREP, List.of(CHUNK_READ),
                List.of(), MergedMethods.Mode.WRAP, List.of("Lcom/mojang/serialization/", "LevelChunkSection", "PalettedContainer", "ChunkTicks", "ListTag", "Heightmap")),
            // any change at all to read counts. A callback could edit the tag before a prepared result is taken.
            new FeatureHook(HookTargets.STORAGE_LOAD_PREP_UNCHANGED, List.of(CHUNK_READ), MergedMethods.Mode.BYPASS))),
        // the load's tag passes through scheduleChunkLoad's stages between the preparation and read
        Map.entry("ChunkMapLoadMixin", List.of(
            new FeatureHook(HookTargets.STORAGE_LOAD_PREP_UNCHANGED, List.of("scheduleChunkLoad(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;"),
                MergedMethods.Mode.BYPASS))),
        // a POI column's read is taken from the read made ahead. tryRead's error handling still applies to it.
        Map.entry("SectionStoragePoiMixin", List.of(
            new FeatureHook(HookTargets.CHUNK_POI_PREFETCH, List.of("tryRead(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;",
                "readColumn(Lnet/minecraft/world/level/ChunkPos;)V"), MergedMethods.Mode.BYPASS))),
        // the packet's block data is taken in place of the two calls. The rest of the constructor runs as vanilla's.
        Map.entry("ChunkPacketDataMixin", List.of(
            new FeatureHook(HookTargets.CHUNK_PACKET_PREP, List.of("<init>(Lnet/minecraft/world/level/chunk/LevelChunk;)V"), List.of(), MergedMethods.Mode.WRAP,
                List.of("ClientboundLevelChunkPacketData")),
            new FeatureHook(HookTargets.CHUNK_PACKET_PREP, List.of("calculateChunkSize(Lnet/minecraft/world/level/chunk/LevelChunk;)I",
                "extractChunkData(Lnet/minecraft/network/FriendlyByteBuf;Lnet/minecraft/world/level/chunk/LevelChunk;)V"), MergedMethods.Mode.BYPASS))),
        Map.entry("SectionVersionMixin", List.of(
            new FeatureHook(HookTargets.CHUNK_PACKET_PREP, List.of("write(Lnet/minecraft/network/FriendlyByteBuf;)V", "getSerializedSize()I"),
                MergedMethods.Mode.BYPASS))),
        Map.entry("EntityLoadBudgetMixin", List.of(
            new FeatureHook(HookTargets.CHUNK_ENTITY_BUDGET, List.of("processPendingLoads()V"), MergedMethods.Mode.BYPASS))),
        // a chunk asked off the server thread that already has the status skips the method's body (the task on the server thread)
        Map.entry("ServerChunkCachePresentMixin", List.of(
            new FeatureHook(HookTargets.CHUNK_PRESENT_OFF_THREAD,
                List.of("getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;"), MergedMethods.Mode.BYPASS))),
        // a loaded section's palettes are decoded entry by entry, then built by unpack, as the codecs would
        Map.entry("PaletteCodecInspectMixin", List.of(
            new FeatureHook(HookTargets.STORAGE_FAST_PALETTES, List.of(
                "codecRW(Lnet/minecraft/core/IdMap;Lcom/mojang/serialization/Codec;Lnet/minecraft/world/level/chunk/PalettedContainer$Strategy;Ljava/lang/Object;)Lcom/mojang/serialization/Codec;",
                "codecRO(Lnet/minecraft/core/IdMap;Lcom/mojang/serialization/Codec;Lnet/minecraft/world/level/chunk/PalettedContainer$Strategy;Ljava/lang/Object;)Lcom/mojang/serialization/Codec;",
                "codec(Lnet/minecraft/core/IdMap;Lcom/mojang/serialization/Codec;Lnet/minecraft/world/level/chunk/PalettedContainer$Strategy;Ljava/lang/Object;"
                    + "Lnet/minecraft/world/level/chunk/PalettedContainerRO$Unpacker;)Lcom/mojang/serialization/Codec;",
                "unpack(Lnet/minecraft/core/IdMap;Lnet/minecraft/world/level/chunk/PalettedContainer$Strategy;Lnet/minecraft/world/level/chunk/PalettedContainerRO$PackedData;)"
                    + "Lcom/mojang/serialization/DataResult;"), MergedMethods.Mode.BYPASS))),
        Map.entry("ChunkSerializerCodecInspectMixin", List.of(
            new FeatureHook(HookTargets.STORAGE_FAST_PALETTES, List.of("<clinit>()V", "makeBiomeCodec(Lnet/minecraft/core/Registry;)Lcom/mojang/serialization/Codec;"),
                MergedMethods.Mode.BYPASS))),
        Map.entry("HeightmapPrimeInspectMixin", List.of(
            new FeatureHook(HookTargets.STORAGE_PRIME_HEIGHTMAPS, List.of("primeHeightmaps(Lnet/minecraft/world/level/chunk/ChunkAccess;Ljava/util/Set;)V"),
                MergedMethods.Mode.BYPASS))),
        Map.entry("RegionFileAccessor", List.of(
            new FeatureHook(HookTargets.STORAGE_REGION_WRITE, List.of("getChunkDataOutputStream(Lnet/minecraft/world/level/ChunkPos;)Ljava/io/DataOutputStream;")))),
        // region files synced in batches (storage.RegionSync): any other change to how the file is written or flushed keeps vanilla's write-through
        Map.entry("RegionFileSyncMixin", List.of(
            new FeatureHook(HookTargets.STORAGE_REGION_SYNC, List.of("write(Lnet/minecraft/world/level/ChunkPos;Ljava/nio/ByteBuffer;)V",
                "clear(Lnet/minecraft/world/level/ChunkPos;)V", "flush()V", "close()V", "writeHeader()V", "padToFullSector()V")))),
        // the section tags are made inside write by wrapping the two codec calls. The rest of write runs as vanilla's.
        // a saved chunk's post-processing lists are packed by GA2's code in place of packOffsets' body
        Map.entry("ChunkSerializerOffsetsMixin", List.of(
            new FeatureHook(HookTargets.STORAGE_POST_PROCESSING, List.of("packOffsets([Lit/unimi/dsi/fastutil/shorts/ShortList;)Lnet/minecraft/nbt/ListTag;"),
                MergedMethods.Mode.BYPASS))),
        Map.entry("ChunkSerializerMixin", List.of(
            new FeatureHook(HookTargets.STORAGE_SECTION_CODEC, List.of("write(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;)Lnet/minecraft/nbt/CompoundTag;",
                "makeBiomeCodec(Lnet/minecraft/core/Registry;)Lcom/mojang/serialization/Codec;"), List.of(), MergedMethods.Mode.WRAP,
                // the wrapped calls are the codecs' encodeStart. C2ME's redirects of the block entity calls in write do not touch them
                List.of("Lcom/mojang/serialization/", "PalettedContainer")))),
        // the unload task is queued through a wrapped executor. The task itself runs as vanilla's. processUnloads and tick get
        // a callback at their head and a wrapper.
        Map.entry("ChunkMapSavePrepMixin", List.of(
            new FeatureHook(HookTargets.STORAGE_SAVE_PREP, List.of("scheduleUnload(JLnet/minecraft/server/level/ChunkHolder;)V",
                "processUnloads(Ljava/util/function/BooleanSupplier;)V", "tick(Ljava/util/function/BooleanSupplier;)V"),
                List.of(), MergedMethods.Mode.WRAP, List.of("Ljava/util/concurrent/CompletableFuture;")))),
        // the tick lists. When encoded ahead, they are taken in place of the containers' save calls. The encoding writes what
        // vanilla's save methods write, so a change to those methods leaves the save to them.
        Map.entry("ChunkSerializerTicksMixin", List.of(
            new FeatureHook(HookTargets.STORAGE_SAVE_PREP_TICKS, List.of("saveTicks(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/world/level/chunk/ChunkAccess$TicksToSave;)V"),
                List.of(), MergedMethods.Mode.WRAP, List.of("Lnet/minecraft/world/ticks/SerializableTickContainer;")))),
        Map.entry("LevelChunkTicksPrepMixin", List.of(
            new FeatureHook(HookTargets.STORAGE_SAVE_PREP_TICKS, List.of("save(JLjava/util/function/Function;)Lnet/minecraft/nbt/ListTag;"), MergedMethods.Mode.BYPASS))),
        Map.entry("ProtoChunkTicksPrepMixin", List.of(
            new FeatureHook(HookTargets.STORAGE_SAVE_PREP_TICKS, List.of("save(JLjava/util/function/Function;)Lnet/minecraft/nbt/Tag;"), MergedMethods.Mode.BYPASS))),
        Map.entry("IOWorkerMixin", List.of(
            // loadAsync's task is vanilla's up to the parse. submitTask and the pending writes are used as vanilla uses them.
            new FeatureHook(HookTargets.STORAGE_PARALLEL_READ, List.of("loadAsync(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;"),
                List.of("submitTask(Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;")),
            new FeatureHook(HookTargets.STORAGE_PRECOMPRESS, List.of("<init>(Lnet/minecraft/world/level/chunk/storage/RegionStorageInfo;Ljava/nio/file/Path;Z)V", "store(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)Ljava/util/concurrent/CompletableFuture;",
                "runStore(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/chunk/storage/IOWorker$PendingStore;)V")),
            // the scan asks the worker's own isOldChunk (Tectonic's version check there keeps working)
            new FeatureHook(HookTargets.STORAGE_OLD_CHUNK_SCAN, List.of("<init>(Lnet/minecraft/world/level/chunk/storage/RegionStorageInfo;Ljava/nio/file/Path;Z)V", "createOldDataForRegion(II)Ljava/util/concurrent/CompletableFuture;",
                "scanChunk(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/StreamTagVisitor;)Ljava/util/concurrent/CompletableFuture;"),
                List.of("isOldChunk(Lnet/minecraft/nbt/CompoundTag;)Z")))));

    @Override
    public void onLoad(String mixinPackage) {
        // inspect each class once MixinExtras' late injectors are in place too (see LateInspection)
        LateInspection.register((target, mixin) -> inspect(target, mixin, true));
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    /**
     * GA2 mixins apply, with two exceptions. The mailbox, main thread executor and unload mixins stay out under C2ME or Moonrise, whose chunk systems
     * schedule that work themselves. With takeover off, the DFC's NoiseChunk and climate hooks step aside for the mods
     * known to patch that code. Otherwise, overlapping mixins of other performance mods are cancelled instead
     * (compat/Takeover through MixinSquared).
     */
    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith(".StructureTemplatePlaceMixin") || mixinClassName.endsWith(".StructureTemplatePlaceNeoMixin")) {
            // the loader's own processBlockInfos call: vanilla's five arguments on Fabric, NeoForge's six
            return mixinClassName.endsWith("Neo" + "Mixin") == Platform.isNeoForge();
        }
        if ((mixinClassName.endsWith(".ChunkMapMailboxMixin") || mixinClassName.endsWith(".MainThreadExecutorMixin")
            || mixinClassName.endsWith(".ChunkMapUnloadMixin") || mixinClassName.endsWith(".ThreadedLightTasksMixin")
            || mixinClassName.endsWith(".ChunkMapLightTasksMixin") || mixinClassName.endsWith(".ThreadedLightLayersMixin"))
            && (Compat.isLoaded("c2me") || Compat.isLoaded("moonrise"))) {
            // their chunk systems schedule these mailboxes, their light engines and their unloads themselves
            return false;
        }
        if (mixinClassName.contains(".mixin.chunkload.") && !mixinClassName.endsWith(".ClientChunkPacketTimingMixin")
            && ChunkLoadConfig.otherChunkSystem()) {
            // their chunk systems load, ticket and send chunks themselves
            return false;
        }
        if (!mixinClassName.contains(".mixin.dfc.") || Takeover.enabled()) {
            return true;
        }
        String conflict = Compat.conflictFor(mixinClassName);
        if (conflict != null) {
            LOGGER.warn("skip {}: '{}' also patches {}, takeover off -> foreign path",
                mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1), conflict, targetClassName);
            return false;
        }
        return true;
    }

    /**
     * {@code otherTargets} holds every class some other mixin config will patch. The aquifer and
     * beardifier fast paths replace a whole method at its head, so they stand down for a class
     * another mod patches (unless forced) and keep that mod's changes in effect.
     */
    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        MixinTargets.record(otherTargets);
    }

    private static final String FLUID_STATUS = "Lnet/minecraft/world/level/levelgen/Aquifer$FluidStatus;";
    /** Aquifer methods the fast path takes over (computeSubstance and everything it calls). */
    private static final List<String> AQUIFER_METHODS = List.of(
        "computeSubstance(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;D)Lnet/minecraft/world/level/block/state/BlockState;",
        "shouldScheduleFluidUpdate()Z",
        "calculatePressure(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;Lorg/apache/commons/lang3/mutable/MutableDouble;" + FLUID_STATUS
            + FLUID_STATUS + ")D",
        "getAquiferStatus(J)" + FLUID_STATUS,
        "computeFluid(III)" + FLUID_STATUS,
        "computeSurfaceLevel(III" + FLUID_STATUS + "IZ)I",
        "computeRandomizedFluidSurfaceLevel(IIII)I",
        "computeFluidType(III" + FLUID_STATUS + "I)Lnet/minecraft/world/level/block/state/BlockState;",
        "similarity(II)D", "gridX(I)I", "gridY(I)I", "gridZ(I)I", "getIndex(III)I");
    private static final String BEARDIFIER_COMPUTE = "compute(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D";
    /** The formulas the piece index and the culling bounds take from vanilla (compute itself is read by BeardifierChain). */
    private static final List<String> BEARDIFIER_STATICS = List.of(
        "getBuryContribution(DDD)D", "getBeardContribution(IIII)D", "computeBeardContribution(III)D", "isInKernelRange(I)Z");

    @Override
    public List<String> getMixins() {
        return null;
    }

    /**
     * Records, for each hook, which other mods' mixins change the methods the hook replaces. Mixin calls the
     * {@code preApply} of every config for a class before it applies any mixin to the class, so the class node seen there
     * is still vanilla. {@code postApply} runs once the regular mixins of every mod are applied, but before MixinExtras
     * inserts its late injectors. The inspection itself therefore waits for {@link LateInspection}, a transformer
     * extension that runs after the one of MixinExtras. The node then holds all the changes of the mixins.
     * {@link MergedMethods} decides which of them a fast path would skip. The answers are runtime switches the hooks read.
     * NoiseChunk is patched by many mods, so the shared surface level cache looks at the two methods it hooks rather than
     * the whole class.
     */
    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // the class as the transformers left it, before any mixin. Records what coremods changed in it (Coremods).
        Coremods.inspect(targetClassName, targetClass);
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (LateInspection.registered()) {
            LateInspection.queue(targetClassName, mixinClassName);
        } else {
            inspect(targetClass, mixinClassName, false);
        }
    }

    /**
     * Puts GA2's replacements of whole vanilla methods ({@link LateOverwrites}) in the methods that no other mod or coremod changed.
     * Any change to those methods, or to what they call, counts. Runs only once the class is complete ({@code late}). Before that,
     * MixinExtras' late injectors still point into vanilla's body. Without the late pass, vanilla's methods stay.
     */
    private static void installOverwrites(ClassNode targetClass, String mixinClassName, boolean late) {
        String simpleName = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        List<String> methods = LateOverwrites.methods(simpleName);
        if (methods.isEmpty() || !mixinClassName.startsWith(OWN_MIXIN_PACKAGE)) {
            return;
        }
        List<FeatureHook> hooks = STEP_HOOKS.getOrDefault(simpleName, List.of());
        List<String> changedBy = Coremods.withNames(targetClass.name, methods,
            unhandled(MergedMethods.foreignCalls(targetClass, methods, List.of(), MergedMethods.Mode.BYPASS, List.of(), OWN_MIXIN_PACKAGE), hooks));
        boolean forced = !hooks.isEmpty() && Boolean.getBoolean(HookTargets.forceProperty(hooks.get(0).name()));
        if (!late) {
            LOGGER.warn("{}: fast {} off (late mixin inspection unavailable)",
                Names.mojangClass(targetClass.name), methods);
        } else if (changedBy.isEmpty() || forced) {
            LateOverwrites.install(targetClass, methods);
        } else {
            LOGGER.info("{}: {} shared with {} -> GA2 versions not applied",
                Names.mojangClass(targetClass.name), methods, changedBy);
        }
    }

    /**
     * The mixins among {@code changes} whose changes no known entry of {@code hooks} handles. A handled change is one GA2's
     * replacement reproduces (Fabric API's air test in the section write), so it does not keep the replacement out.
     */
    private static List<String> unhandled(List<MergedMethods.Change> changes, List<FeatureHook> hooks) {
        Map<String, List<String>> byMixin = new LinkedHashMap<>();
        for (MergedMethods.Change c : changes) {
            int paren = c.method().indexOf('(');
            byMixin.computeIfAbsent(c.mixin(), k -> new ArrayList<>()).add(paren < 0 ? c.method() : c.method().substring(0, paren));
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : byMixin.entrySet()) {
            boolean known = false;
            for (FeatureHook h : hooks) {
                if (KnownMixins.find(h.name(), e.getKey(), e.getValue()) != null) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    private static void inspect(ClassNode targetClass, String mixinClassName, boolean late) {
        installOverwrites(targetClass, mixinClassName, late);
        if (mixinClassName.endsWith(".mixin.aquifer.NoiseBasedAquiferMixin")) {
            // changes known to keep every status vanilla's when statuses are computed in vanilla's order. With them, the fast path only searches.
            Map<String, List<String>> byMixin = new LinkedHashMap<>();
            for (MergedMethods.Change c : MergedMethods.foreignCalls(targetClass, AQUIFER_METHODS, List.of(), MergedMethods.Mode.REPLACE, List.of(), OWN_MIXIN_PACKAGE)) {
                byMixin.computeIfAbsent(c.mixin(), k -> new ArrayList<>()).add(c.method().substring(0, c.method().indexOf('(')));
            }
            List<String> other = new ArrayList<>();
            List<String> orderKept = new ArrayList<>();
            List<String> liquidRegions = new ArrayList<>();
            for (Map.Entry<String, List<String>> e : byMixin.entrySet()) {
                boolean known = KnownMixins.find(
                    YungsCaveBiomesMixins.AQUIFER_ORDER_KEPT, e.getKey(), e.getValue()) != null;
                // Better Caves' step on the answer, which GA2 applies to its own answers (aquifer.LiquidRegions)
                boolean liquid = !known && KnownMixins.find(
                    BetterCavesMixins.AQUIFER_LIQUID_REGIONS, e.getKey(), e.getValue()) != null;
                (known ? orderKept : liquid ? liquidRegions : other).add(e.getKey());
            }
            MixinTargets.recordAquifer(Coremods.withNames(targetClass.name, AQUIFER_METHODS, other), orderKept, liquidRegions);
            return;
        }
        if (mixinClassName.endsWith(".mixin.beardifier.BeardifierMixin")) {
            // compute: what other mods add around GA2's callback (BeardifierChain). The index runs the steps it knows itself.
            BeardifierChain.Recorded chain = BeardifierChain.inspect(targetClass, OWN_MIXIN_PACKAGE);
            List<String> computeForeign = new ArrayList<>(chain.unknown());
            for (Coremods.Change c : Coremods.changes(targetClass.name, BEARDIFIER_COMPUTE)) {
                boolean stepOfChain = chain.steps().stream().anyMatch(s -> s.kind() == BeardifierChain.Kind.COREMOD && c.mixin().equals("coremod " + s.owner()));
                if (!stepOfChain && !computeForeign.contains(c.mixin())) {
                    computeForeign.add(c.mixin());
                }
            }
            List<String> index = new ArrayList<>(Coremods.withNames(targetClass.name, BEARDIFIER_STATICS,
                MergedMethods.foreignChanges(targetClass, BEARDIFIER_STATICS, OWN_MIXIN_PACKAGE)));
            computeForeign.stream().filter(m -> !index.contains(m)).forEach(index::add);
            MixinTargets.recordBeardifier(index);
            // the noise programs skip compute for cells no vanilla piece reaches. Any change to the formulas counts wherever it
            // sits. Other mods' code before GA2's callback counts too, because a skipped cell does not run it. The steps after
            // the callback and the wrappers around the method are looked at per beardifier (BeardExtras).
            List<String> culling = new ArrayList<>(Coremods.withNames(targetClass.name, BEARDIFIER_STATICS,
                MergedMethods.foreignChanges(targetClass, BEARDIFIER_STATICS, List.of(), MergedMethods.Mode.BYPASS, OWN_MIXIN_PACKAGE)));
            for (String m : computeForeign) {
                if (!culling.contains(m)) {
                    culling.add(m);
                }
            }
            for (String m : chain.before()) {
                if (!culling.contains(m)) {
                    culling.add(m);
                }
            }
            MixinTargets.recordBeardCulling(culling);
            if (!chain.steps().isEmpty() || !chain.outer().isEmpty()) {
                LOGGER.info("Beardifier.compute wrapped by {}, post-sum steps {}{}", chain.outer(), chain.steps(),
                    chain.understood() ? " -> run by piece index" : " -> partially unsupported by piece index: " + chain.unknown());
            }
            return;
        }
        List<FeatureHook> stepHooks = STEP_HOOKS.get(mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1));
        if (stepHooks != null && mixinClassName.startsWith(OWN_MIXIN_PACKAGE)) {
            for (FeatureHook hook : stepHooks) {
                HookTargets.recordChanges(hook.name(), Coremods.with(targetClass.name, hook.methods(), hook.inspectCalls(targetClass)));
            }
            return;
        }
        if (mixinClassName.endsWith(".mixin.features.predicate.BlockPredicatesInspectMixin")) {
            // one key per vanilla predicate class. The predicate programs ask a changed predicate class through its own test.
            FeatureTargets.recordChanges(PredicatePrograms.predicateKey(targetClass.name),
                Coremods.with(targetClass.name, List.of("test"),
                    MergedMethods.foreignCalls(targetClass, List.of("test"), List.of(), MergedMethods.Mode.BYPASS, List.of(), OWN_MIXIN_PACKAGE)));
            return;
        }
        if (mixinClassName.endsWith(".mixin.features.PlacementModifiersInspectMixin")) {
            // one key per transcribed modifier class. The pipeline sends a changed modifier class through its own methods.
            FeatureTargets.recordChanges(FeatureTargets.modifierKey(targetClass.name), Coremods.with(targetClass.name, MODIFIER_METHODS,
                MergedMethods.foreignCalls(targetClass, MODIFIER_METHODS, List.of(), MergedMethods.Mode.BYPASS, List.of(), OWN_MIXIN_PACKAGE)));
            return;
        }
        FeatureHook featureHook = FEATURE_HOOKS.get(mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1));
        if (featureHook != null && mixinClassName.contains(".mixin.features.")) {
            FeatureTargets.recordChanges(featureHook.name(), Coremods.with(targetClass.name, featureHook.methods(), featureHook.inspectCalls(targetClass)));
            return;
        }
        if (mixinClassName.endsWith(".mixin.aquifer.NoiseChunkSurfaceMixin")) {
            List<String> foreign = Coremods.withNames(targetClass.name, SURFACE_LEVEL_METHODS,
                MergedMethods.foreignChanges(targetClass, SURFACE_LEVEL_METHODS, OWN_MIXIN_PACKAGE));
            MixinTargets.recordSurfaceLevel(foreign);
            if (!foreign.isEmpty()) {
                if (Boolean.getBoolean(MixinTargets.SURFACE_LEVEL_FORCE)) {
                    LOGGER.warn("NoiseChunk surface levels conflict with {} -> surface level cache kept (forced: -D{}=true)",
                        foreign, MixinTargets.SURFACE_LEVEL_FORCE);
                } else {
                    LOGGER.warn("NoiseChunk surface levels conflict with {} -> surface level cache disabled (force: -D{}=true)",
                        foreign, MixinTargets.SURFACE_LEVEL_FORCE);
                }
            }
        }
    }

}
