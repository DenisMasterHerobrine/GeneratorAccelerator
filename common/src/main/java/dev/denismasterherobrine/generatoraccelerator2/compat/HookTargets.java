package dev.denismasterherobrine.generatoraccelerator2.compat;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;

/**
 * Per-method stand-down record for the scheduling, surface and carver hooks, keyed by
 * {@code "<area>.<hook>"}. The mixin plugin inspects each hooked vanilla method just before this
 * mod's late mixin is applied. The plugin records the foreign mixins that changed the method. The hook
 * then keeps that mod's code path unless forced with {@code -Dgeneratoraccelerator2.<area>.<hook>.force=true}.
 * Mixins of mods GA2 takes over from are cancelled before this point and never show up here.
 * Loaded by the mixin plugin, so it must not reference Minecraft types.
 */
public final class HookTargets {
    private static final Logger LOGGER = Ga2Log.get("Compat");
    private static final Map<String, List<String>> FOREIGN = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> INSPECTED = new ConcurrentHashMap<>();
    /** Answers of {@link #allowed} once the hook was inspected. Hot paths ask on every call. */
    private static final Map<String, Boolean> ALLOWED = new ConcurrentHashMap<>();
    /** The {@code .force} property per key, read once (system properties are set at launch). */
    private static final Map<String, Boolean> FORCED = new ConcurrentHashMap<>();
    /** Handles of keys asked on hot paths ({@link #hook}). */
    private static final Map<String, Hook> HOOKS = new ConcurrentHashMap<>();

    private HookTargets() {}

    /**
     * A key asked on a hot path (per block, per biome lookup, per placement). {@link #allowed} and {@link #unchanged} answer
     * as {@link HookTargets#allowed} and {@link HookTargets#unchanged} do. Once an answer is known, it comes from a field
     * instead of a lookup by name on every call. Recording the key again (another class's inspection) forgets the handle's
     * answers, as it forgets the answer kept by {@link HookTargets#allowed}.
     */
    public static final class Hook {
        private final String key;
        /** 0 not known yet, 1 yes, 2 no. */
        private volatile byte allowed;
        private volatile byte unchanged;

        private Hook(String key) {
            this.key = key;
        }

        public boolean allowed() {
            byte a = this.allowed;
            if (a != 0) {
                return a == 1;
            }
            Boolean known = ALLOWED.get(this.key);
            if (known != null) {
                this.allowed = known ? (byte) 1 : (byte) 2;
                return known;
            }
            // computes, keeps and hands this handle the answer once the key was inspected
            return HookTargets.allowed(this.key);
        }

        public boolean unchanged() {
            byte u = this.unchanged;
            if (u != 0) {
                return u == 1;
            }
            boolean answer = HookTargets.unchanged(this.key);
            this.unchanged = answer ? (byte) 1 : (byte) 2;
            return answer;
        }
    }

    /** The hot-path handle of {@code key}, one per key. */
    public static Hook hook(String key) {
        return HOOKS.computeIfAbsent(key, Hook::new);
    }

    private static boolean forced(String key) {
        Boolean forced = FORCED.get(key);
        if (forced == null) {
            forced = Boolean.getBoolean(forceProperty(key));
            FORCED.put(key, forced);
        }
        return forced;
    }

    public static final String SURFACE_ENTRY = "scheduling.surfaceEntry";
    /** {@code ChunkStatusTasks.generateSurface}: other mods' code after the generator call keeps the call inline. */
    public static final String SURFACE_STEP = "scheduling.surfaceStep";
    /**
     * {@code ChunkStatusTasks.generateSurface}, run by GA2 itself. Other mods' code before the generator call therefore moves to
     * the worker together with the call ({@code scheduling.StepHandlers}). Every change another mod makes to the method must be
     * handled.
     */
    public static final String SURFACE_STEP_BODY = "scheduling.surfaceStepBody";
    /** {@code ChunkStatusTasks.generateCarvers}, likewise. */
    public static final String CARVER_STEP = "scheduling.carverStep";
    public static final String CARVER_ENTRY = "carvers.applyCarvers";
    /** The vanilla carver classes whose work the carver runner replays. Any change to these classes keeps those carvers on vanilla's code. */
    public static final String CARVER_REPLAY = "carvers.replay";
    /**
     * {@code SurfaceSystem.topMaterial} and {@code CarvingContext.topMaterial}. The carver runner builds the surface rules once per chunk
     * for the top materials below carved grass, instead of once per block. Any change to either method keeps vanilla's call.
     */
    public static final String CARVER_TOP_MATERIAL = "carvers.topMaterial";
    /**
     * Zeta's cluster walk ({@code ClusterBasedGenerator.generateChunkPart} and its lambda, {@code forEachChunkBlock}, {@code ClusterShape.isInside})
     * and Quark's big stone cluster test ({@code canPlaceBlock} and the test context's {@code canPlaceAt}). GA2 walks a cluster part only
     * where the cluster can reach ({@code compat.zeta.ZetaClusters}). Any change to these methods keeps Zeta's own loop.
     */
    public static final String ZETA_CLUSTERS = "zeta.clusters";
    /**
     * {@code ServerLevel.onBlockStateChange}, {@code PoiTypes.forState} and the call in {@code WorldGenRegion.setBlock}. When a generation
     * region writes between two states without a POI type, the write leaves the call out ({@code state.PoiStates}). Any change to these
     * methods keeps every call.
     */
    public static final String STATE_POI_SKIP = "state.poiSkip";
    public static final String SURFACE_BUILD = "surface.buildSurface";
    public static final String SURFACE_TOP_MATERIAL = "surface.topMaterial";
    public static final String SURFACE_BIOME_ZOOM = "surface.biomeZoom";
    public static final String BIOME_CLIMATE_SEARCH = "biomes.climateSearch";
    /** {@code MultiNoiseBiomeSource.parameters()}: once the parameter list is known, it is returned without the method's own work (compat). */
    public static final String BIOME_PARAMETERS = "biomes.parameters";
    public static final String NOISE_FILL = "noise.fill";
    /**
     * {@code NoiseChunk.updateForX} and {@code updateForY}, which GA2's own hooks replace. The noise fill then steps the
     * interpolators itself, without a callback object per block row ({@code FastFill}).
     */
    public static final String NOISE_FILL_STEPS = "noise.fillSteps";
    /** {@code NoiseBasedChunkGenerator.fillFromNoise}, answered from the GPU (gpu.GpuSteps) at its head. */
    public static final String GPU_NOISE = "gpu.noise";
    /**
     * {@code NoiseBasedChunkGenerator.createBiomes}, answered from the GPU at the method's head ({@code doCreateBiomes} skipped). Also
     * covers what the GPU computes itself: {@code MultiNoiseBiomeSource.getNoiseBiome} with its parameter list, and
     * {@code Climate.Sampler.sample}.
     */
    public static final String GPU_BIOMES = "gpu.biomes";
    /**
     * {@code Climate.Sampler.sample}, whose answers the GPU hands to the CPU's own biome lookups at the chunk's quarts
     * ({@code gpu.runtime.BiomeHand}) when other mods change the lookup ({@link #GPU_BIOMES} off).
     */
    public static final String GPU_CLIMATE_POINTS = "gpu.climatePoints";
    /**
     * A chunk the GPU filled goes without a NoiseChunk ({@code gpu.GpuTerrain}). Covers the surface step's {@code getOrCreateNoiseChunk} call
     * and the rule context's {@code preliminarySurfaceLevel} calls, which are wrapped.
     */
    public static final String GPU_TERRAIN = "gpu.terrain";
    /** The surface step answered from the GPU at the head of {@code buildSurface(ChunkAccess, ...)}, with the biome temperature the GPU computes itself. */
    public static final String GPU_SURFACE = "gpu.surface";
    /** {@code Holder.Reference.is(TagKey)} and {@code bindTags}: tag membership by bit (state.TagBits). */
    public static final String TAG_BITS = "tags.bits";
    /** {@code LevelChunkSection.setBlockState}: the counted state properties from per-state flags (state.StateFlags). */
    public static final String SECTION_WRITE = "state.sectionWrite";
    /**
     * {@code LevelChunkSection.setBlockState(x, y, z, state)}, the locking form that vanilla's {@code ProtoChunk.setBlockState} calls.
     * GA2's exact writer calls the form without the container's thread check instead, because decorations already hold their area
     * locks. The exception is when another mod changes the locking form.
     */
    public static final String SECTION_WRITE_UNLOCKED = "state.sectionWriteUnlocked";
    /** {@code PalettedContainer.Data.copyFrom}, a container's resize: one palette lookup per distinct old id ({@code mixin.state.PaletteCopyMixin}). */
    public static final String PALETTE_COPY = "state.paletteCopy";
    /** {@code LinearPalette} and {@code HashMapPalette}: {@code idFor} remembering its last answer. */
    public static final String PALETTE = "state.palette";
    /** {@code FluidState.isEmpty} remembered per state. */
    public static final String FLUID_EMPTY = "state.fluidEmpty";
    /**
     * The fluid tick call in {@code LevelChunk.postProcessGeneration}, and the water's tick behind it ({@code FlowingFluid},
     * {@code WaterFluid}). The tick of water that cannot flow is left out ({@code fluid.StillWater}). Any change keeps every tick.
     */
    public static final String FLUID_POST_PROCESS = "fluids.postProcess";
    /**
     * {@code LiquidBlockRenderer.tesselate} and its face checks, and its call in {@code BlockRenderDispatcher.renderLiquid}. Water
     * with water on all six sides is not passed to the renderer ({@code fluid.StillWater}). Any change keeps vanilla's whole method.
     */
    public static final String FLUID_HIDDEN_WATER = "fluids.hiddenWater";
    /** {@code ChunkAccess.getMinBuildHeight} and {@code getHeight} read from the height accessor once per chunk. */
    public static final String CHUNK_HEIGHTS = "state.chunkHeights";
    /** {@code Block.isShapeFullBlock} remembered on the shape. */
    public static final String FULL_BLOCK_SHAPE = "state.fullBlockShape";
    /** The per-block state FastFill computes in place ({@code getInterpolatedState} and what that runs). When changed, FastFill asks the real method. */
    public static final String NOISE_FILL_STATE = "noise.fillState";
    /** {@code MaterialRuleList.calculate} walked by index. */
    public static final String NOISE_MATERIAL_RULES = "noise.materialRules";
    /** Vanilla's ore vein rule ({@code OreVeinifier}). When changed, FastFill runs the rule for every solid block instead of skipping whole cells. */
    public static final String NOISE_VEIN_RULE = "noise.veinRule";
    /** {@code LevelChunkSection.setBlockState}, whose work the noise fill's section writes do ({@code SectionWriter}). When changed, the fill writes block by block. */
    public static final String NOISE_SECTION_WRITES = "noise.sectionWrites";
    /** {@code StructureTemplate.filterBlocks}, answered from a per-palette cache for jigsaw blocks. When changed, every call runs the method. */
    public static final String STRUCTURE_JIGSAW_BLOCKS = "structures.jigsawBlocks";
    /**
     * {@code StructureTemplate.placeInWorld}'s call of {@code processBlockInfos}, that method and the position transform, whose work
     * {@code structure.TemplatePlacement} does for the blocks inside the bounding box only. When changed, every placement processes the whole template.
     */
    public static final String STRUCTURE_TEMPLATE_CLIP = "structures.templateClip";
    /** Nether fossil placement ({@code NetherFossilStructure.findGenerationPoint} and the biome check behind it). When changed, every probe runs. */
    public static final String STRUCTURE_FOSSIL_PROBE = "structures.fossilProbe";
    /** Where ring placements (strongholds) can put positions ({@code RingBounds}). When the ring computation or the check is changed, every check waits for the positions. */
    public static final String STRUCTURE_RING_BOUND = "structures.ringBound";
    /** {@code NoiseBasedChunkGenerator.getBaseHeight} and {@code iterateNoiseColumn}, whose answers {@code structure.HeightProbes} remembers. */
    public static final String STRUCTURE_HEIGHT_PROBE = "structures.heightProbe";
    /** {@code NoiseBasedChunkGenerator.createFluidPicker} and its lambda: the picker whose fluids depend on y alone. Whole-cell air relies on that. */
    public static final String NOISE_FLUID_PICKER = "noise.fluidPicker";
    public static final String STORAGE_PACK = "storage.pack";
    /** {@code RegionFile}'s writes, header, flush and close, and {@code RegionFileStorage.getRegionFile}: files synced in batches ({@code storage.RegionSync}). */
    public static final String STORAGE_REGION_SYNC = "storage.regionSync";
    public static final String STORAGE_REGION_WRITE = "storage.regionWrite";
    public static final String STORAGE_OLD_CHUNK_SCAN = "storage.oldChunkScan";
    public static final String STORAGE_PRECOMPRESS = "storage.precompress";
    public static final String STORAGE_SECTION_CODEC = "storage.sectionCodec";
    /** {@code ChunkMap.scheduleUnload}, {@code processUnloads} and {@code tick}: unloading chunks' sections encoded on the worker pool before their save ({@code storage.SavePrep}). */
    public static final String STORAGE_SAVE_PREP = "storage.savePrep";
    /**
     * The tick lists' save methods ({@code ChunkSerializer.saveTicks}, {@code LevelChunkTicks.save}, {@code ProtoChunkTicks.save}). Another
     * mod's change to these methods leaves the ticks to the save. The sections are still encoded ahead ({@link #STORAGE_SAVE_PREP}). An
     * example is Lithium's tick scheduler, which keeps its own queues and writes them in its own order.
     */
    public static final String STORAGE_SAVE_PREP_TICKS = "storage.savePrepTicks";
    /** {@code IOWorker.loadAsync} and {@code RegionFileStorage.read}: a loaded chunk's bytes read by the IO thread, decompressed and parsed on the worker pool ({@code storage.ParallelRead}). */
    public static final String STORAGE_PARALLEL_READ = "storage.parallelRead";
    /** {@code ChunkSerializer.read}: section palettes, sections and tick lists of a loaded chunk prepared on the worker pool ({@code storage.LoadPrep}). */
    public static final String STORAGE_LOAD_PREP = "storage.loadPrep";
    /** {@code ChunkSerializer.read} and {@code ChunkMap.scheduleChunkLoad} changed by no other mod at all. Nothing can then edit a loaded chunk's tag between preparation and {@code read}. */
    public static final String STORAGE_LOAD_PREP_UNCHANGED = "storage.loadPrepUnchanged";
    /**
     * {@code PalettedContainer}'s codec builders and {@code unpack}, and the section codecs of {@code ChunkSerializer}: a loaded section's
     * palettes decoded without the codecs' records and lists, each entry by the element codec ({@code storage.FastPalettes}).
     */
    public static final String STORAGE_FAST_PALETTES = "storage.fastPalettes";
    /** {@code Heightmap.primeHeightmaps}, skipped by {@code read} when there is nothing to prime. */
    public static final String STORAGE_PRIME_HEIGHTMAPS = "storage.primeHeightmaps";
    /** {@code SectionStorage.tryRead}: a POI column's read taken from the read made ahead with its chunk ({@code chunkload.PoiPrefetch}). */
    public static final String CHUNK_POI_PREFETCH = "chunkLoad.poiPrefetch";
    /** The chunk packet's block data ({@code ClientboundLevelChunkPacketData}, the section writes): written ahead in parallel ({@code chunkload.ChunkPackets}). */
    public static final String CHUNK_PACKET_PREP = "chunkLoad.packetPrep";
    /** {@code PersistentEntitySectionManager.processPendingLoads}: loaded chunks' entities added within a budget per tick. */
    public static final String CHUNK_ENTITY_BUDGET = "chunkLoad.entityBudget";
    /**
     * {@code ServerChunkCache.getChunk(x, z, status, load)} off the server thread: a chunk that already has the status is
     * answered at once instead of through a task on the server thread ({@code chunkload.PresentChunks}).
     */
    public static final String CHUNK_PRESENT_OFF_THREAD = "chunkLoad.presentOffThread";
    public static final String LIGHT_ENGINE = "light.engine";
    public static final String LIGHT_SOURCES = "light.sources";
    /** The propagation methods the parallel light propagation runs itself ({@code parallellight}). When changed, every batch runs vanilla's. */
    public static final String LIGHT_PARALLEL = "light.parallel";
    /**
     * {@code LayerLightSectionStorage.swapSectionMap}, the light maps' {@code copy} and the methods that change the working
     * section map: light published from the changed sections ({@code light.LightSnapshot}). When changed, every publish is
     * vanilla's clone of the whole map.
     */
    public static final String LIGHT_PUBLISH = "light.publish";
    /**
     * {@code ThreadedLevelLightEngine.addTask} and {@code tryScheduleUpdate}: light tasks handed straight to the light thread
     * ({@code light.LightTasks}). When changed, tasks go through vanilla's chunk priority sorter.
     */
    public static final String LIGHT_TASKS = "light.tasks";
    /**
     * {@code LevelLightEngine}'s calls to both layers, its {@code runLightUpdates}, and {@code ThreadedLevelLightEngine.runUpdate}:
     * block light and sky light of an update at once ({@code light.LightLayers}). When changed, the layers update one after the other.
     */
    public static final String LIGHT_LAYERS = "light.layers";
    /** {@code MinecraftServer.prepareLevels}: the spawn area the server waits for before players can join ({@code join.JoinWait}). */
    public static final String JOIN_SPAWN_WAIT = "join.spawnWait";

    /** Handled mixins of each hook ({@link KnownMixins}), with their changes in execution order. */
    private static final Map<String, List<Handled>> HANDLED = new ConcurrentHashMap<>();

    /** A handled mixin's change to a hooked method: what the inspection found, and the entry that covers it. */
    public record Handled(String mixin, String method, int at, KnownMixins.Entry entry) {}

    /**
     * Records the inspection of a hook's methods. Changes of mixins that {@link KnownMixins} covers are handled: GA2 runs them
     * in its fast path (see {@link CompatAdapters}). Any other mixin makes the hook stand down.
     */
    public static void recordChanges(String key, List<MergedMethods.Change> changes) {
        java.util.Map<String, List<MergedMethods.Change>> byMixin = new java.util.LinkedHashMap<>();
        for (MergedMethods.Change c : changes) {
            byMixin.computeIfAbsent(c.mixin(), k -> new java.util.ArrayList<>()).add(c);
        }
        List<String> foreign = new java.util.ArrayList<>();
        for (Map.Entry<String, List<MergedMethods.Change>> e : byMixin.entrySet()) {
            KnownMixins.Entry known = KnownMixins.find(key, e.getKey(), e.getValue().stream().map(c -> c.method().substring(0, c.method().indexOf('('))).toList());
            if (known == null) {
                foreign.add(e.getKey());
                continue;
            }
            List<Handled> handled = e.getValue().stream().map(c -> new Handled(c.mixin(), c.method(), c.at(), known)).toList();
            HANDLED.merge(key, handled, (a, b) -> java.util.stream.Stream.concat(a.stream(), b.stream())
                .sorted(java.util.Comparator.comparingInt(Handled::at)).toList());
            LOGGER.info("hook '{}' shared with {} ({}) -> kept, emulated: {}",
                key, e.getKey(), known.modId(), known.what());
        }
        record(key, foreign);
    }

    /** The handled changes of a hook, in execution order (empty when none). */
    public static List<Handled> handled(String key) {
        return HANDLED.getOrDefault(key, Collections.emptyList());
    }

    /** The handled mixins of a hook, once each. */
    public static List<String> handledMixins(String key) {
        return handled(key).stream().map(Handled::mixin).distinct().toList();
    }

    public static Map<String, List<Handled>> allHandled() {
        return Collections.unmodifiableMap(HANDLED);
    }

    /** YUNG's API's change to the per-block state, which FastFill runs itself (beardifier.YungPieces). */
    public static final String YUNG_NOISE_CHUNK = "com.yungnickyoung.minecraft.yungsapi.mixin.NoiseChunkMixin";

    /** Iron's Spellbooks' change to the per-block state, which FastFill runs itself too ({@code dfc.runtime.FillSteps}). */
    public static final String IRONS_NOISE_CHUNK = "io.redspace.ironsspellbooks.mixin.NoiseChunkMixin";
    /** The changes to the per-block state that FastFill runs itself, in the chunks where those changes act (their fluid steps). */
    private static final java.util.Set<String> FILL_STEP_MIXINS = java.util.Set.of(YUNG_NOISE_CHUNK, IRONS_NOISE_CHUNK);

    /** True when every change to the per-block state is one FastFill handles per chunk (YUNG's API, Iron's Spellbooks). */
    public static boolean fillStateKnown() {
        List<String> foreign = foreign(NOISE_FILL_STATE);
        return !foreign.isEmpty() && FILL_STEP_MIXINS.containsAll(foreign);
    }

    public static void record(String key, List<String> foreignMixins) {
        try {
            if (key.equals(NOISE_FILL_STATE) && !foreignMixins.isEmpty() && FILL_STEP_MIXINS.containsAll(foreignMixins) && !forced(key)) {
                FOREIGN.merge(key, List.copyOf(foreignMixins), (a, b) -> java.util.stream.Stream.concat(a.stream(), b.stream()).distinct().toList());
                LOGGER.info("hook '{}' shared with {} -> kept, fluid steps run in GA2 fill (chunks with their structures)", key, foreignMixins);
                return;
            }
            if (!foreignMixins.isEmpty()) {
                // a key may be recorded for several classes or methods: the findings add up
                FOREIGN.merge(key, List.copyOf(foreignMixins), (a, b) -> java.util.stream.Stream.concat(a.stream(), b.stream()).distinct().toList());
                if (forced(key)) {
                    LOGGER.warn("hook '{}' conflicts with {} -> kept (forced: -D{}=true)", key, foreignMixins, forceProperty(key));
                } else {
                    LOGGER.warn("hook '{}' conflicts with {} -> disabled (force: -D{}=true)",
                        key, foreignMixins, forceProperty(key));
                }
            }
        } finally {
            // the answers kept so far are forgotten once the findings they come from are in
            INSPECTED.put(key, Boolean.TRUE);
            ALLOWED.remove(key);
            Hook hook = HOOKS.get(key);
            if (hook != null) {
                hook.allowed = 0;
                hook.unchanged = 0;
            }
        }
    }

    public static String forceProperty(String key) {
        return "generatoraccelerator2." + key + ".force";
    }

    /**
     * True when the hook was inspected and no foreign mixin touched the hook's target method, except handled mixins whose
     * compat code is ready. Also true when the hook is forced on.
     */
    public static boolean allowed(String key) {
        Boolean known = ALLOWED.get(key);
        if (known != null) {
            return known;
        }
        boolean forced = forced(key);
        if (!forced && !INSPECTED.containsKey(key)) {
            return false;
        }
        boolean allowed = forced || !FOREIGN.containsKey(key) && CompatAdapters.ready(key, handledMixins(key));
        ALLOWED.put(key, allowed);
        Hook hook = HOOKS.get(key);
        if (hook != null) {
            hook.allowed = allowed ? (byte) 1 : (byte) 2;
        }
        return allowed;
    }

    /** True when no foreign mixin touched the method. Also true when the method was never inspected, because nothing is known to differ. */
    public static boolean unchanged(String key) {
        return forced(key) || !FOREIGN.containsKey(key);
    }

    public static boolean inspected(String key) {
        return INSPECTED.containsKey(key);
    }

    public static List<String> foreign(String key) {
        return FOREIGN.getOrDefault(key, Collections.emptyList());
    }

    public static Map<String, List<String>> allForeign() {
        return Collections.unmodifiableMap(FOREIGN);
    }

    /** Hooks asked for whose classes were not inspected yet (not loaded, or loaded without GA2's mixin). These hooks read as off. Sorted. */
    public static List<String> undecided() {
        List<String> out = new java.util.ArrayList<>();
        for (String key : HOOKS.keySet()) {
            if (!INSPECTED.containsKey(key) && !forced(key)) {
                out.add(key);
            }
        }
        Collections.sort(out);
        return out;
    }
}
