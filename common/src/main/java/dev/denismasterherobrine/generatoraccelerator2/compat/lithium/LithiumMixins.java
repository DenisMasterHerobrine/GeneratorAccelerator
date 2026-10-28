package dev.denismasterherobrine.generatoraccelerator2.compat.lithium;

import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Mixins of Lithium and its Forge ports (Radium, Canary) that GA2 handles ({@link KnownMixins}). Radium keeps Lithium's
 * classes, Canary has its own. Contains no Minecraft types, because the mixin plugin reads it.
 */
public final class LithiumMixins {
    private LithiumMixins() {}

    public static final String MOD_ID = "lithium";
    /** Lithium's Forge ports. */
    public static final List<String> PORTS = List.of("radium", "canary");

    /**
     * In {@code LevelChunkSection.setBlockState(x, y, z, state)}, redirects the call of the locking form to the form without
     * the container's thread check. GA2's exact writer makes that same call in place of the method.
     */
    public static final String NO_LOCKING = "me.jellysquid.mods.lithium.mixin.chunk.no_locking.ChunkSectionMixin";
    /** Canary's copy of {@link #NO_LOCKING}. */
    public static final String CANARY_NO_LOCKING = "com.abdelaziz.canary.mixin.chunk.no_locking.LevelChunkSectionMixin";

    /**
     * Lithium's {@code world.temperature_cache}. It overwrites {@code Biome.getTemperature} to return
     * {@code getHeightAdjustedTemperature} without vanilla's per-thread cache of that value. ModernFix's
     * {@code remove_biome_temperature_cache} does the same. Every temperature therefore stays vanilla's. GA2's GPU surface
     * computes the height-adjusted temperature itself, and it stays exact because the values match.
     */
    public static final String TEMPERATURE = "me.jellysquid.mods.lithium.mixin.world.temperature_cache.BiomeMixin";

    /**
     * {@code Biome.getTemperature} overwritten, by the name it has at runtime: Mojang's in development, intermediary on
     * Fabric, SRG on Forge.
     */
    public static final Set<String> GET_TEMPERATURE = Set.of(KnownMixins.overwrite("getTemperature"), KnownMixins.overwrite("method_21740"),
        KnownMixins.overwrite("m_47505_"));

    /**
     * Lithium's {@code util.block_tracking}: a callback in {@code LevelChunkSection.setBlockState} that keeps the section's block
     * counters up to date, while they are filled. GA2's noise fill writes a section's palette at once and then drops the
     * counters, which Lithium counts again from the palette ({@link LithiumSections}).
     */
    public static final String BLOCK_TRACKING = "me.jellysquid.mods.lithium.mixin.util.block_tracking.ChunkSectionMixin";
    /** Canary's copy of {@link #BLOCK_TRACKING}. */
    public static final String CANARY_BLOCK_TRACKING = "com.abdelaziz.canary.mixin.util.block_tracking.LevelChunkSectionMixin";

    /**
     * Lithium's {@code world.chunk_access}: an overwrite of {@code ServerChunkCache.getChunk(x, z, status, load)} with its own
     * cache of the last chunks asked on the server thread. Off the server thread it hands the call to the server thread as
     * vanilla does. GA2's callback at the head of the method still answers a chunk that already has the status first.
     */
    public static final String CHUNK_ACCESS = "me.jellysquid.mods.lithium.mixin.world.chunk_access.ServerChunkManagerMixin";
    /** Canary's copy of {@link #CHUNK_ACCESS}. */
    public static final String CANARY_CHUNK_ACCESS = "com.abdelaziz.canary.mixin.world.chunk_access.ServerChunkCacheMixin";
    /** The overwritten {@code getChunk} by its runtime name, and the overwrite's own methods it calls. */
    private static final Set<String> GET_CHUNK = Set.of(KnownMixins.overwrite("getChunk"), KnownMixins.overwrite("method_12121"),
        KnownMixins.overwrite("m_7587_"), KnownMixins.overwrite("getChunkOffThread"), KnownMixins.overwrite("getChunkBlocking"),
        KnownMixins.overwrite("createChunkLoadTicket"), KnownMixins.overwrite("addToCache"), KnownMixins.overwrite("createCacheKey"));

    /**
     * Lithium's {@code world.chunk_ticking.spread_ice}: redirects in {@code Biome.shouldFreeze} that read the fluid once. GA2's snow
     * and freeze path only leaves out columns too warm to freeze, which {@code shouldFreeze} turns away before those redirects.
     */
    public static final String SPREAD_ICE = "me.jellysquid.mods.lithium.mixin.world.chunk_ticking.spread_ice.BiomeMixin";
    /** Canary's copy of {@link #SPREAD_ICE}. */
    public static final String CANARY_SPREAD_ICE = "com.abdelaziz.canary.mixin.world.chunk_ticking.spread_ice.BiomeMixin";
    private static final Set<String> SPREAD_ICE_HANDLERS = Set.of("getNull", "skipFluidCheck", "fluidCheckAndGetBlock");

    /** Lithium's {@code block.flatten_states}: {@code FluidState.isEmpty} from a field. GA2's version caches the same answer. */
    public static final String FLATTEN_STATES = "me.jellysquid.mods.lithium.mixin.block.flatten_states.FluidStateMixin";
    /** Canary's copy of {@link #FLATTEN_STATES}. */
    public static final String CANARY_FLATTEN_STATES = "com.abdelaziz.canary.mixin.block.flatten_states.FluidStateMixin";
    private static final Set<String> IS_EMPTY = Set.of(KnownMixins.overwrite("isEmpty"), KnownMixins.overwrite("method_15769"),
        KnownMixins.overwrite("m_76178_"));

    /** Lithium's {@code shapes.blockstate_cache}: {@code Block.isShapeFullBlock} from a lossy table. GA2's version keeps the same answer on the shape. */
    public static final String BLOCKSTATE_CACHE = "me.jellysquid.mods.lithium.mixin.shapes.blockstate_cache.BlockMixin";
    /** Canary's copy of {@link #BLOCKSTATE_CACHE}. */
    public static final String CANARY_BLOCKSTATE_CACHE = "com.abdelaziz.canary.mixin.shapes.blockstate_cache.BlockMixin";
    private static final Set<String> IS_SHAPE_FULL_BLOCK = Set.of(KnownMixins.overwrite("isShapeFullBlock"), KnownMixins.overwrite("method_9614"),
        KnownMixins.overwrite("m_49916_"));

    /**
     * Lithium's {@code chunk.no_locking} for containers: {@code PalettedContainer.acquire} and {@code release} do nothing. Those
     * only detect misuse from two threads, and GA2's packet preparation writes each container from one thread.
     */
    public static final String NO_LOCKING_CONTAINER = "me.jellysquid.mods.lithium.mixin.chunk.no_locking.PalettedContainerMixin";
    /** Canary's copy of {@link #NO_LOCKING_CONTAINER}. */
    public static final String CANARY_NO_LOCKING_CONTAINER = "com.abdelaziz.canary.mixin.chunk.no_locking.PalettedContainerMixin";
    private static final Set<String> ACQUIRE_RELEASE = Set.of(KnownMixins.overwrite("acquire"), KnownMixins.overwrite("release"),
        KnownMixins.overwrite("method_12334"), KnownMixins.overwrite("method_12335"), KnownMixins.overwrite("m_63084_"), KnownMixins.overwrite("m_63120_"));

    /**
     * Lithium's {@code world.tick_scheduler}: a chunk's scheduled ticks in its own sorted queues, saved in the order {@code getAll}
     * gives. GA2's save preparation reads the ticks through {@code getAll} then (storage.SavePrep).
     */
    public static final String TICK_SCHEDULER = "me.jellysquid.mods.lithium.mixin.world.tick_scheduler.ChunkTickSchedulerMixin";
    /** Canary's copy of {@link #TICK_SCHEDULER}. */
    public static final String CANARY_TICK_SCHEDULER = "com.abdelaziz.canary.mixin.world.tick_scheduler.LevelChunkTicksMixin";
    private static final Set<String> SAVE = Set.of(KnownMixins.overwrite("save"), KnownMixins.overwrite("method_39365"), KnownMixins.overwrite("m_183237_"));

    /** The signature GA2's section write calls Lithium's counter callback with (Mojang names). */
    private static final String TRACKER_DESC = "(IIILnet/minecraft/world/level/block/state/BlockState;ZLorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;"
        + "Lnet/minecraft/world/level/block/state/BlockState;)V";

    /**
     * False when {@code target} has Lithium's counter callback with another signature than GA2's section write calls. GA2's body
     * replaces vanilla's, and with it the callback's call ({@code LithiumSections.afterSwap}), so it then stays out.
     */
    public static boolean overwriteCallsFit(ClassNode target) {
        for (MethodNode m : target.methods) {
            if (m.name.endsWith("$updateFlagCounters") && !Names.mojangDesc(m.desc).equals(TRACKER_DESC)) {
                return false;
            }
        }
        return true;
    }

    private static final String COUNTERS = "Lithium's block counters stay right. GA2 writes new terrain at once and lets Lithium count the blocks again.";
    private static final String CHUNKS = "Lithium's faster chunk lookups still work. GA2 still answers a chunk another thread asks for at once when it is there.";
    private static final String ICE = "Lithium's faster ice check still works. GA2 still skips columns too warm to freeze, which never reach it.";
    private static final String EMPTY = "Fluid emptiness is cached by GA2 instead of Lithium. The answers are the same.";
    private static final String SHAPES = "Full block shapes are cached by GA2 instead of Lithium. The answers are the same.";
    private static final String TRACKING = "Lithium's block counters stay right. GA2's own block write calls Lithium's counter update where vanilla's does.";
    private static final String LOCKS = "Lithium's lock-free block containers still work. GA2 prepares chunk packets from one thread per container.";
    private static final String TICKS = "Lithium's tick scheduler still works. GA2 encodes the scheduled ticks ahead in the order Lithium saves them.";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.NOISE_SECTION_WRITES, BLOCK_TRACKING, Set.of("updateFlagCounters"), COUNTERS),
        new KnownMixins.Entry("canary", HookTargets.NOISE_SECTION_WRITES, CANARY_BLOCK_TRACKING, Set.of("updateFlagCounters"), COUNTERS),
        new KnownMixins.Entry(MOD_ID, HookTargets.CHUNK_PRESENT_OFF_THREAD, CHUNK_ACCESS, GET_CHUNK, CHUNKS),
        new KnownMixins.Entry("canary", HookTargets.CHUNK_PRESENT_OFF_THREAD, CANARY_CHUNK_ACCESS, GET_CHUNK, CHUNKS),
        new KnownMixins.Entry(MOD_ID, HookTargets.SECTION_WRITE_UNLOCKED, NO_LOCKING, Set.of("setBlockStateNoLocking"),
            "Lithium's faster block writes still work. GA2's own block writer makes the same call."),
        new KnownMixins.Entry("canary", HookTargets.SECTION_WRITE_UNLOCKED, CANARY_NO_LOCKING, Set.of("setBlockStateNoLocking"),
            "Canary's faster block writes still work. GA2's own block writer makes the same call."),
        new KnownMixins.Entry(MOD_ID, HookTargets.GPU_SURFACE, TEMPERATURE, GET_TEMPERATURE,
            "Lithium's biome temperature change still works. It gives the same values, so GA2's GPU surface stays exact."),
        new KnownMixins.Entry(MOD_ID, FeatureTargets.compatKey("climate"), TEMPERATURE, GET_TEMPERATURE,
            "Lithium's biome temperature change still works. It gives the same values, so GA2 still skips columns too warm to freeze."),
        new KnownMixins.Entry(MOD_ID, FeatureTargets.compatKey("climate"), SPREAD_ICE, SPREAD_ICE_HANDLERS, ICE),
        new KnownMixins.Entry("canary", FeatureTargets.compatKey("climate"), CANARY_SPREAD_ICE, SPREAD_ICE_HANDLERS, ICE),
        new KnownMixins.Entry(MOD_ID, HookTargets.FLUID_EMPTY, FLATTEN_STATES, IS_EMPTY, EMPTY),
        new KnownMixins.Entry("canary", HookTargets.FLUID_EMPTY, CANARY_FLATTEN_STATES, IS_EMPTY, EMPTY),
        new KnownMixins.Entry(MOD_ID, HookTargets.FULL_BLOCK_SHAPE, BLOCKSTATE_CACHE, IS_SHAPE_FULL_BLOCK, SHAPES),
        new KnownMixins.Entry("canary", HookTargets.FULL_BLOCK_SHAPE, CANARY_BLOCKSTATE_CACHE, IS_SHAPE_FULL_BLOCK, SHAPES),
        new KnownMixins.Entry(MOD_ID, HookTargets.SECTION_WRITE, BLOCK_TRACKING, Set.of("updateFlagCounters"), TRACKING),
        new KnownMixins.Entry("canary", HookTargets.SECTION_WRITE, CANARY_BLOCK_TRACKING, Set.of("updateFlagCounters"), TRACKING),
        new KnownMixins.Entry(MOD_ID, HookTargets.CHUNK_PACKET_PREP, NO_LOCKING_CONTAINER, ACQUIRE_RELEASE, LOCKS),
        new KnownMixins.Entry("canary", HookTargets.CHUNK_PACKET_PREP, CANARY_NO_LOCKING_CONTAINER, ACQUIRE_RELEASE, LOCKS),
        new KnownMixins.Entry(MOD_ID, HookTargets.STORAGE_SAVE_PREP_TICKS, TICK_SCHEDULER, SAVE, TICKS),
        new KnownMixins.Entry("canary", HookTargets.STORAGE_SAVE_PREP_TICKS, CANARY_TICK_SCHEDULER, SAVE, TICKS));

    /**
     * GA2's writer makes the same call that Lithium's redirect makes. The noise fill needs Lithium's counters to be found, GA2's own
     * section write needs Lithium's counter callback. The others give answers GA2 gives too.
     */
    public static void register() {
        for (KnownMixins.Entry e : ENTRIES) {
            BooleanSupplier ready = e.hook().equals(HookTargets.NOISE_SECTION_WRITES) ? LithiumSections::tracked
                : e.hook().equals(HookTargets.SECTION_WRITE) ? LithiumSections::trackerFound : () -> true;
            CompatAdapters.register(e.hook(), e.mixin(), ready);
        }
    }
}
