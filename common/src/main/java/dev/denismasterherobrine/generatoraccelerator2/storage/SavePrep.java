package dev.denismasterherobrine.generatoraccelerator2.storage;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.chunkio.VerifyCounter;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.memory.MemoryPressure;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.SavedTick;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.SerializableTickContainer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.slf4j.Logger;

/**
 * Encodes the section tags of an unloading chunk on a worker pool before the server thread saves the chunk.
 *
 * <p>Saving a chunk on the server thread ({@code ChunkSerializer.write}) is mostly encoding the block states and biomes of
 * its sections. Vanilla queues a chunk's unload task once the chunk's work is done and its holder waits to unload. At that
 * point a worker copies the two containers of each section ({@code PalettedContainer.Data.copy}: the storage's longs and
 * the palette). The worker encodes the copies exactly as the save would ({@link FastPack}, {@link SectionCodec}). The copy
 * and its tag are left on the container.
 * When the save reaches the container, the server thread compares the container with the copy. Both must have the same
 * storage class and bit width, the same longs, the same palette size and the same value for every palette id. Two global
 * palettes both use the registry's ids. The tag is a function of the values held by the 4096 (or 64) entries. Equal data
 * therefore gives the tag the save would build, and the save takes it. Anything else is encoded as before, e.g. a chunk
 * written to in between or a copy torn by a writer. The copy is read without the container's lock. Whatever the copy
 * holds, the comparison decides.
 *
 * <p>Vanilla runs a chunk's unload task in the same tick it is queued. A task whose sections are still in encoding therefore
 * waits for the next tick ({@code mixin.storage.ChunkMapSavePrepMixin}). It waits at most {@link #MAX_DEFERRALS} times and
 * only while the chunk map ticks. The saves at shutdown and on {@code /save-all flush} never wait. Everything else in the
 * task runs on the server thread in vanilla's order: the unload event, the rest of the tag, NeoForge's save event, and the
 * light and POI updates.
 *
 * <p>{@code -Dgeneratoraccelerator2.savePrep=false} turns it off. {@code -Dgeneratoraccelerator2.verify.savePrep=N} also
 * encodes every N-th taken tag on the server thread and compares the two.
 */
public final class SavePrep {
    private SavePrep() {}

    private static final Logger LOGGER = Ga2Log.get("Storage");

    public static boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.savePrep"));

    /**
     * Maximum ticks an unload task waits for its sections. A square of generated chunks unloads its ring of partly generated
     * neighbours all at once (thousands of chunks). The encoding threads take a few ticks to go through them.
     */
    public static final int MAX_DEFERRALS = 10;
    /**
     * Upper bound on chunks queued or in encoding. Above it, the server thread encodes as before. Memory scales with active
     * encoder threads, not queue depth. A queued entry retains only its chunk, which the unload already retains. Section
     * copies and tags live from encode start until the save consumes them (~1 tick).
     */
    static final int MAX_IN_FLIGHT = 16384;
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    /**
     * Upper bound on unload tasks that have an encoding started and have not run yet. A busy server keeps about 2000 unload
     * tasks queued. While the tick has no time left, {@code processUnloads} runs only the tasks past that count. Every queued
     * task would hold the copies and tags of its sections (about 100 KB a chunk). Above this bound, the save encodes as before.
     * {@code -Dgeneratoraccelerator2.savePrep.maxQueued=N} sets it.
     */
    static final int MAX_QUEUED = Math.max(1, Integer.getInteger("generatoraccelerator2.savePrep.maxQueued", 512));
    /** {@link #MAX_QUEUED} while the heap is nearly full ({@link MemoryPressure}). */
    static final int MAX_QUEUED_PRESSURE = Math.min(MAX_QUEUED, 32);
    private static final AtomicInteger QUEUED = new AtomicInteger();
    /**
     * Dedicated threads: a quarter of the processor threads, 1 to 4. On the worker pool, the encodings waited behind the
     * generation steps queued there, longer than their unloads can wait.
     */
    private static final int THREADS = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 4));
    private static final AtomicInteger THREAD_IDS = new AtomicInteger();
    private static final ThreadPoolExecutor POOL = pool();

    private static ThreadPoolExecutor pool() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(THREADS, THREADS, 30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), r -> {
                Thread t = new Thread(r, "GA2 Save Prep #" + THREAD_IDS.incrementAndGet());
                t.setDaemon(true);
                t.setUncaughtExceptionHandler((th, e) -> LOGGER.error("uncaught exception on {}", th.getName(), e));
                return t;
            });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    public static final LongAdder CHUNKS = new LongAdder();
    public static final LongAdder TAKEN = new LongAdder();
    public static final LongAdder CHANGED = new LongAdder();
    public static final LongAdder WAITED = new LongAdder();
    public static final LongAdder LATE = new LongAdder();
    public static final LongAdder BUSY = new LongAdder();
    private static final VerifyCounter VERIFY = new VerifyCounter("savePrep");
    private static volatile boolean failureLogged;

    /** What a container holds between its encoding on a worker and its save ({@code mixin.storage.PalettedContainerPrepMixin}). */
    public interface Slot {
        @Nullable
        Prepared ga2$prepared();

        void ga2$setPrepared(@Nullable Prepared prepared);
    }

    /** A container's copy and the tag encoded from it. */
    public record Prepared(PalettedContainer.Data<?> copy, Tag tag) {}

    /** A chunk's block or fluid ticks between their encoding on a worker and the save ({@code mixin.storage.*TicksPrepMixin}). */
    public interface TickSlot {
        @Nullable
        PreparedTicks ga2$preparedTicks();

        void ga2$setPreparedTicks(@Nullable PreparedTicks prepared);

        /** The ticks {@code save} writes, in its order ({@code SavedTick}s, then {@code ScheduledTick}s). */
        Object[] ga2$tickSnapshot();

        /** Whether {@code save} would write exactly these tick objects, in this order. */
        boolean ga2$sameTicks(Object[] snapshot);
    }

    /** The tick objects a list was encoded from, the game time the scheduled tick delays count from, and the list. */
    public record PreparedTicks(Object[] ticks, long gameTime, ListTag tag) {}

    /** Scheduled ticks are encoded ahead too. {@code -Dgeneratoraccelerator2.savePrep.ticks=false} leaves them to the save. */
    static final boolean TICKS = !"false".equals(System.getProperty("generatoraccelerator2.savePrep.ticks"));
    /** If another mod changes the save methods of the tick lists (e.g. Lithium's scheduler), the ticks are left to the save. */
    private static final HookTargets.Hook TICKS_HOOK = HookTargets.hook(HookTargets.STORAGE_SAVE_PREP_TICKS);

    static boolean ticksAhead() {
        return TICKS && TICKS_HOOK.allowed();
    }
    public static final LongAdder TICKS_TAKEN = new LongAdder();
    public static final LongAdder TICKS_CHANGED = new LongAdder();
    private static final VerifyCounter VERIFY_TICKS = new VerifyCounter("savePrepTicks");

    @SuppressWarnings({ "unchecked", "rawtypes" })
    static <T> void prepareTicks(SerializableTickContainer<T> container, Function<T, String> id, long gameTime) {
        if (!(container instanceof TickSlot slot)) {
            return;
        }
        Object[] ticks = slot.ga2$tickSnapshot();
        ListTag list = new ListTag();
        for (Object tick : ticks) {
            // as LevelChunkTicks.save and ProtoChunkTicks.save write them
            list.add(tick instanceof ScheduledTick scheduled ? SavedTick.saveTick(scheduled, (Function) id, gameTime)
                : ((SavedTick) tick).save((Function) id));
        }
        slot.ga2$setPreparedTicks(new PreparedTicks(ticks, gameTime, list));
    }

    /**
     * Returns the tick list encoded ahead for {@code container} if the container still holds the same tick objects in the
     * same order. The scheduled tick delays are counted from {@code gameTime}, as the save counts them. Returns null
     * otherwise. Server thread.
     */
    @Nullable
    public static Tag takeTicks(SerializableTickContainer<?> container, long gameTime, Supplier<Tag> encode) {
        if (!(container instanceof TickSlot slot) || !ticksAhead()) {
            return null;
        }
        PreparedTicks prepared = slot.ga2$preparedTicks();
        if (prepared == null) {
            return null;
        }
        slot.ga2$setPreparedTicks(null);
        boolean same;
        try {
            same = slot.ga2$sameTicks(prepared.ticks());
        } catch (RuntimeException e) {
            same = false;
        }
        if (!same) {
            TICKS_CHANGED.increment();
            return null;
        }
        ListTag list = prepared.tag();
        if (gameTime != prepared.gameTime()) {
            Object[] ticks = prepared.ticks();
            for (int i = 0; i < ticks.length; i++) {
                if (ticks[i] instanceof ScheduledTick<?> scheduled) {
                    // SavedTick.saveTick: the delay from the game time of the save
                    ((CompoundTag) list.get(i)).putInt("t", (int) (scheduled.triggerTick() - gameTime));
                }
            }
        }
        TICKS_TAKEN.increment();
        if (VERIFY_TICKS.sample()) {
            Tag expected = encode.get();
            VERIFY_TICKS.result(list.equals(expected), "ticks", expected, list);
        }
        return list;
    }

    /** An unload task that may wait for its sections (main thread). */
    public interface Owner {
        boolean ga2$mayDefer();

        void ga2$defer(Runnable task);
    }

    public static boolean enabled() {
        return ENABLED && StorageConfig.savePrep() && SectionCodec.enabled() && FastPack.enabled() && HookTargets.allowed(HookTargets.STORAGE_SAVE_PREP);
    }

    /**
     * Starts encoding the sections of the chunk in {@code holder}. That chunk is about to be saved and unloaded. Returns null
     * when there is nothing to encode or too much is in flight.
     */
    @Nullable
    public static CompletableFuture<Void> start(ServerLevel level, ChunkHolder holder) {
        ChunkAccess chunk = holder.getLatestChunk();
        if (chunk == null || !chunk.isUnsaved() || !holder.isReadyForSaving()) {
            return null;
        }
        if (QUEUED.incrementAndGet() > (MemoryPressure.high() ? MAX_QUEUED_PRESSURE : MAX_QUEUED)) {
            QUEUED.decrementAndGet();
            BUSY.increment();
            return null;
        }
        if (IN_FLIGHT.incrementAndGet() > MAX_IN_FLIGHT) {
            IN_FLIGHT.decrementAndGet();
            QUEUED.decrementAndGet();
            BUSY.increment();
            return null;
        }
        Registry<Biome> biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        long gameTime = level.getGameTime();
        CompletableFuture<Void> job = new CompletableFuture<>();
        // A save that stopped waiting completes the job. The queued encoding then releases the unloaded chunk.
        AtomicReference<ChunkAccess> pending = new AtomicReference<>(chunk);
        job.whenComplete((v, t) -> pending.set(null));
        try {
            POOL.execute(() -> {
                try {
                    ChunkAccess c = pending.get();
                    if (c != null) {
                        prepare(c, biomes, gameTime, job);
                    }
                } catch (Throwable t) {
                    if (!failureLogged) {
                        failureLogged = true;
                        LOGGER.warn("section pre-encode failed -> encoded on save", t);
                    }
                } finally {
                    IN_FLIGHT.decrementAndGet();
                    job.complete(null);
                }
            });
        } catch (RuntimeException e) {
            IN_FLIGHT.decrementAndGet();
            QUEUED.decrementAndGet();
            return null;
        }
        return job;
    }

    /** Copies and encodes the section containers and tick lists of {@code chunk} until its save stops waiting. Worker thread. */
    static void prepare(ChunkAccess chunk, Registry<Biome> biomes, long gameTime, CompletableFuture<Void> job) {
        if (!prepare(chunk, biomes, job)) {
            return;
        }
        if (ticksAhead()) {
            ChunkAccess.TicksToSave ticks = chunk.getTicksForSerialization();
            prepareTicks(ticks.blocks(), SavePrep::blockId, gameTime);
            prepareTicks(ticks.fluids(), SavePrep::fluidId, gameTime);
        }
    }

    /**
     * Type names of ticks, built once per type instead of once per tick. A name is
     * {@code BuiltInRegistries.BLOCK/FLUID.getKey(type).toString()}, which the save writes as the tick's {@code i}. The cached
     * string is identical, and strings are immutable.
     */
    private static final Map<Object, String> TICK_IDS = new ConcurrentHashMap<>();

    private static String blockId(Block block) {
        String id = TICK_IDS.get(block);
        if (id == null) {
            id = BuiltInRegistries.BLOCK.getKey(block).toString();
            TICK_IDS.putIfAbsent(block, id);
        }
        return id;
    }

    private static String fluidId(Fluid fluid) {
        String id = TICK_IDS.get(fluid);
        if (id == null) {
            id = BuiltInRegistries.FLUID.getKey(fluid).toString();
            TICK_IDS.putIfAbsent(fluid, id);
        }
        return id;
    }

    /** Copies and encodes the section containers of {@code chunk}. Worker thread. */
    static void prepare(ChunkAccess chunk, Registry<Biome> biomes) {
        prepare(chunk, biomes, null);
    }

    /** As {@link #prepare(ChunkAccess, Registry)}, but stops and returns false once {@code job} is done (the save went on). */
    static boolean prepare(ChunkAccess chunk, Registry<Biome> biomes, @Nullable CompletableFuture<Void> job) {
        for (LevelChunkSection section : chunk.getSections()) {
            if (job != null && job.isDone()) {
                return false;
            }
            prepareContainer(section.getStates(), data -> SectionCodec.blockStates(FastPack.packRaw(data, Block.BLOCK_STATE_REGISTRY, PalettedContainer.Strategy.SECTION_STATES)));
            if (section.getBiomes() instanceof PalettedContainer<Holder<Biome>> container) {
                prepareContainer(container, data -> SectionCodec.biomes(FastPack.packRaw(data, biomes.asHolderIdMap(), PalettedContainer.Strategy.SECTION_BIOMES)));
            }
        }
        CHUNKS.increment();
        return true;
    }

    /**
     * Drops whatever is still left on the containers and tick lists of {@code chunk} after its unload task ran. Main thread.
     * The save took everything else. What remains comes from a cancelled unload (the chunk was requested again) or from a
     * late encoding.
     */
    static void discard(ChunkAccess chunk) {
        for (LevelChunkSection section : chunk.getSections()) {
            ((Slot) (Object) section.getStates()).ga2$setPrepared(null);
            if (section.getBiomes() instanceof PalettedContainer<?> biomes) {
                ((Slot) (Object) biomes).ga2$setPrepared(null);
            }
        }
        ChunkAccess.TicksToSave ticks = chunk.getTicksForSerialization();
        if (ticks.blocks() instanceof TickSlot blocks) {
            blocks.ga2$setPreparedTicks(null);
        }
        if (ticks.fluids() instanceof TickSlot fluids) {
            fluids.ga2$setPreparedTicks(null);
        }
    }

    /** For a server that stopped with unload tasks still queued (they never run). */
    public static void onServerStopped() {
        QUEUED.set(0);
    }

    /** Copies {@code container}'s data and leaves the copy and its encoding on it. */
    static <T> void prepareContainer(PalettedContainer<T> container, Function<PalettedContainer.Data<T>, Tag> encode) {
        PalettedContainer.Data<T> copy = container.data.copy();
        Tag tag = encode.apply(copy);
        if (tag != null) {
            ((Slot) (Object) container).ga2$setPrepared(new Prepared(copy, tag));
        }
    }

    /**
     * Returns the tag encoded ahead for {@code container} if the container's data still equals the source copy, else null.
     * Server thread, inside the save. {@code encode} is the save's own encoding, used by the verify check.
     */
    @Nullable
    public static Tag take(PalettedContainer<?> container, Supplier<Tag> encode) {
        Slot holder = (Slot) (Object) container;
        Prepared prepared = holder.ga2$prepared();
        if (prepared == null) {
            return null;
        }
        holder.ga2$setPrepared(null);
        boolean same;
        container.acquire();
        try {
            same = same(prepared.copy(), container.data);
        } catch (RuntimeException e) {
            // A palette cannot resolve an id (vanilla throws for an uninitialised one). The save encodes it as before.
            same = false;
        } finally {
            container.release();
        }
        if (!same) {
            CHANGED.increment();
            return null;
        }
        TAKEN.increment();
        if (VERIFY.sample()) {
            Tag expected = encode.get();
            VERIFY.result(prepared.tag().equals(expected), "section", expected, prepared.tag());
        }
        return prepared.tag();
    }

    /** The two containers hold the same value at every entry, read the same way. */
    static boolean same(PalettedContainer.Data<?> a, PalettedContainer.Data<?> b) {
        BitStorage sa = a.storage(), sb = b.storage();
        if (sa.getClass() != sb.getClass() || sa.getBits() != sb.getBits() || sa.getSize() != sb.getSize() || !Arrays.equals(sa.getRaw(), sb.getRaw())) {
            return false;
        }
        Palette<?> pa = a.palette(), pb = b.palette();
        if (pa instanceof GlobalPalette<?> || pb instanceof GlobalPalette<?>) {
            // A global palette maps ids through the registry. A container and its copy share the registry.
            return pa.getClass() == pb.getClass();
        }
        int n = pa.getSize();
        if (pb.getSize() != n) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (pa.valueFor(i) != pb.valueFor(i)) {
                return false;
            }
        }
        return true;
    }

    /** An unload task that waits a tick while its sections are encoded (main thread). */
    public static final class Deferred implements Runnable {
        private final Runnable task;
        private final CompletableFuture<Void> job;
        private final Owner owner;
        private final ChunkHolder holder;
        private int deferrals;

        public Deferred(Runnable task, CompletableFuture<Void> job, Owner owner, ChunkHolder holder) {
            this.task = task;
            this.job = job;
            this.owner = owner;
            this.holder = holder;
        }

        @Override
        public void run() {
            if (!job.isDone()) {
                if (deferrals < MAX_DEFERRALS && owner.ga2$mayDefer()) {
                    deferrals++;
                    WAITED.increment();
                    owner.ga2$defer(this);
                    return;
                }
                // An encoding that has not started never will. A started encoding's tags are checked like any others.
                job.complete(null);
                LATE.increment();
            }
            try {
                task.run();
            } finally {
                QUEUED.decrementAndGet();
                ChunkAccess chunk = holder.getLatestChunk();
                if (chunk != null) {
                    discard(chunk);
                }
            }
        }
    }

    /** For {@code /ga2 steps stats}. */
    public static String stats() {
        return String.format(Locale.ROOT, "sections encoded ahead of saves: %d chunks, %d tags taken, %d changed since (encoded again), %d unloads waited a tick,"
            + " %d not ready in time, %d left to the save (busy); tick lists taken %d, changed since %d", CHUNKS.sum(), TAKEN.sum(), CHANGED.sum(), WAITED.sum(), LATE.sum(),
            BUSY.sum(), TICKS_TAKEN.sum(), TICKS_CHANGED.sum());
    }
}
