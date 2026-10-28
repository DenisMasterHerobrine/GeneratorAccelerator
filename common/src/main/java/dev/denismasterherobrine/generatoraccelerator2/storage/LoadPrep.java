package dev.denismasterherobrine.generatoraccelerator2.storage;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import com.google.common.collect.MapMaker;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import dev.denismasterherobrine.generatoraccelerator2.chunkio.VerifyCounter;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.mixin.storage.ChunkSerializerAccessor;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ProtoChunkTicks;
import org.slf4j.Logger;

/**
 * Loading a saved chunk. {@code ChunkSerializer.read} runs on the server thread. Much of its time there goes to work that
 * depends only on the chunk's tag. It decodes the block state and biome palettes of each section through the codecs. It
 * builds the sections, and the section constructor counts the non-air, ticking and fluid blocks of all 4096 entries. It
 * reads the scheduled block and fluid tick lists. This class does that work on the worker pool once the tag is read and
 * upgraded, before the server thread gets to the chunk. It uses the same codecs and loaders. {@code read} then takes each
 * prepared result in place of the call that would have made it ({@code ChunkSerializerReadMixin}). Each result is the same
 * kind of object, made from the same input by the same code, and is used once. All results of a chunk form one
 * {@link Bundle}, looked up once when {@code read} starts.
 *
 * <p>Exactness: a result is only taken for the very tag object it was made from (identity). Between the preparation and
 * {@code read}, vanilla only reads the tag. If no other mod changes {@code read} or {@code ChunkMap.scheduleChunkLoad} at
 * all ({@code storage.loadPrepUnchanged}), nothing else can change the tag either, and the results are taken as they are.
 * Otherwise another mod's callback in {@code read} could edit the tag in place. In that case only the palettes are
 * prepared, each with a copy of its tag. A palette is taken only while its tag still equals the copy. A decode with errors
 * or partial results is not prepared, so vanilla logs and handles it as before. Tick lists remember the chunk position they
 * were filtered for. Everything else in {@code read} still runs on the server thread in vanilla's order: POI checks, light
 * data, block entities, structures and events. A bundle that is never taken (an abandoned load) is dropped together with
 * its tag (weak keys).
 *
 * <p>Also in {@code read}: {@code Heightmap.primeHeightmaps} with an empty set of heightmaps only reads blocks and changes
 * nothing. It reads from the top of each column down to the first block that is not air. Every full chunk saves all of its
 * heightmaps, so for full chunks the set is always empty. The call is then skipped, unless another mod changes
 * {@code primeHeightmaps}.
 *
 * <p>{@code -Dgeneratoraccelerator2.loadPrep=false} turns it off. {@code -Dgeneratoraccelerator2.verify.loadPrep=N} also
 * decodes every N-th taken palette and section the vanilla way and compares the two.
 */
public final class LoadPrep {
    private LoadPrep() {}

    private static final Logger LOGGER = Ga2Log.get("Storage");

    /** Not final so the tests can compare both ways. */
    public static boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.loadPrep"));
    private static final HookTargets.Hook HOOK = HookTargets.hook(HookTargets.STORAGE_LOAD_PREP);
    private static final HookTargets.Hook UNCHANGED = HookTargets.hook(HookTargets.STORAGE_LOAD_PREP_UNCHANGED);
    private static final HookTargets.Hook PRIME = HookTargets.hook(HookTargets.STORAGE_PRIME_HEIGHTMAPS);
    public static final VerifyCounter VERIFY = new VerifyCounter("loadPrep");

    public static final LongAdder CHUNKS = new LongAdder();
    public static final LongAdder CHECKED_CHUNKS = new LongAdder();
    public static final LongAdder TAKEN = new LongAdder();
    public static final LongAdder CHANGED = new LongAdder();
    public static final LongAdder PRIMES_SKIPPED = new LongAdder();
    private static final AtomicBoolean FAILED_ONCE = new AtomicBoolean();

    /** By the chunk's tag (identity, weak). */
    private static final ConcurrentMap<CompoundTag, Bundle> READY = new MapMaker().weakKeys().makeMap();
    /** The bundle of the chunk {@code read} is reading on this thread. */
    private static final ThreadLocal<Bundle> CURRENT = new ThreadLocal<>();

    /** Everything prepared for one chunk, by section in the order of the tag's section list. */
    public static final class Bundle {
        /** Results are taken only while their tags equal these copies. Set when another mod may change {@code read}. */
        final boolean checked;
        final int count;
        final CompoundTag[] stateTags;
        final CompoundTag[] biomeTags;
        @Nullable final CompoundTag[] stateCopies;
        @Nullable final CompoundTag[] biomeCopies;
        final Object[] states;
        final Object[] biomes;
        final LevelChunkSection[] sections;
        /** The section the next lookup most likely wants. {@code read} goes through the sections in order. */
        int cursor;
        @Nullable ListTag blockTickList;
        @Nullable Object blockTicks;
        @Nullable ListTag fluidTickList;
        @Nullable Object fluidTicks;
        @Nullable ChunkPos tickPos;

        Bundle(int count, boolean checked) {
            this.checked = checked;
            this.count = count;
            this.stateTags = new CompoundTag[count];
            this.biomeTags = new CompoundTag[count];
            this.stateCopies = checked ? new CompoundTag[count] : null;
            this.biomeCopies = checked ? new CompoundTag[count] : null;
            this.states = new Object[count];
            this.biomes = new Object[count];
            this.sections = new LevelChunkSection[count];
        }
    }

    public static boolean enabled() {
        return ENABLED && StorageConfig.loadPrep() && HOOK.allowed();
    }

    /** On the worker pool, with the chunk's upgraded tag before the server thread reads it. */
    public static void prepare(ServerLevel level, ChunkPos pos, CompoundTag chunk) {
        // a chunk another mod's known read edit would change is prepared the checked way (LoadTagEdits)
        prepare(level, level.registryAccess().registryOrThrow(Registries.BIOME), pos, chunk, !UNCHANGED.allowed() || LoadTagEdits.any(chunk));
    }

    /**
     * {@link #prepare(ServerLevel, ChunkPos, CompoundTag)} with the level's height and biome registry, since tests have no
     * level. If {@code checked} is set, keeps copies of the palette tags and prepares no tick lists.
     */
    public static void prepare(LevelHeightAccessor level, Registry<Biome> biomes, ChunkPos pos, CompoundTag chunk, boolean checked) {
        try {
            Bundle bundle = prepareSections(level, biomes, chunk, checked);
            if (!checked) {
                prepareTicks(bundle, pos, chunk);
            }
            READY.put(chunk, bundle);
            CHUNKS.increment();
            if (checked) {
                CHECKED_CHUNKS.increment();
            }
        } catch (Throwable t) {
            // the server thread decodes whatever is missing as vanilla does
            if (FAILED_ONCE.compareAndSet(false, true)) {
                LOGGER.warn("load prep of chunk {} failed -> vanilla read on server thread", pos, t);
            }
        }
    }

    private static Bundle prepareSections(LevelHeightAccessor level, Registry<Biome> registry, CompoundTag chunk, boolean checked) {
        ListTag list = chunk.getList("sections", Tag.TAG_COMPOUND);
        Bundle b = new Bundle(list.size(), checked);
        if (list.isEmpty()) {
            return b;
        }
        Codec<PalettedContainer<BlockState>> statesCodec = ChunkSerializerAccessor.ga2$blockStateCodec();
        Codec<PalettedContainerRO<Holder<Biome>>> biomesCodec = ChunkSerializerAccessor.ga2$makeBiomeCodec(registry);
        int count = level.getSectionsCount();
        boolean fast = FastPalettes.enabled();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag section = list.getCompound(i);
            int index = level.getSectionIndexFromSectionY(section.getByte("Y"));
            if (index < 0 || index >= count) {
                continue;
            }
            if (section.contains("block_states", Tag.TAG_COMPOUND)) {
                CompoundTag tag = section.getCompound("block_states");
                PalettedContainer<BlockState> states = fast ? FastPalettes.states(tag) : null;
                if (states == null) {
                    states = decoded(statesCodec.parse(NbtOps.INSTANCE, tag));
                }
                if (states != null) {
                    b.stateTags[i] = tag;
                    b.states[i] = states;
                    if (checked) {
                        b.stateCopies[i] = tag.copy();
                    }
                }
            }
            if (section.contains("biomes", Tag.TAG_COMPOUND)) {
                CompoundTag tag = section.getCompound("biomes");
                PalettedContainerRO<Holder<Biome>> biomes = fast ? FastPalettes.biomes(registry, tag) : null;
                if (biomes == null) {
                    biomes = decoded(biomesCodec.parse(NbtOps.INSTANCE, tag));
                }
                if (biomes != null) {
                    b.biomeTags[i] = tag;
                    b.biomes[i] = biomes;
                    if (checked) {
                        b.biomeCopies[i] = tag.copy();
                    }
                }
            }
            if (b.states[i] != null && b.biomes[i] != null) {
                @SuppressWarnings("unchecked")
                LevelChunkSection s = new LevelChunkSection((PalettedContainer<BlockState>) b.states[i], (PalettedContainerRO<Holder<Biome>>) b.biomes[i]);
                b.sections[i] = s;
            }
        }
        return b;
    }

    /** The value of a decode without errors. Partial results and errors are left to vanilla. */
    @Nullable
    private static <T> T decoded(DataResult<T> result) {
        Optional<T> value = result.result();
        return value.isPresent() && result.error().isEmpty() ? value.get() : null;
    }

    private static void prepareTicks(Bundle b, ChunkPos pos, CompoundTag chunk) {
        boolean full = ChunkSerializer.getChunkTypeFromTag(chunk) == ChunkStatus.ChunkType.LEVELCHUNK;
        b.tickPos = pos;
        ListTag blocks = chunk.getList("block_ticks", Tag.TAG_COMPOUND);
        // For an empty or missing list, getList returns a new empty list. Nothing could match that list by identity.
        if (!blocks.isEmpty()) {
            b.blockTickList = blocks;
            b.blockTicks = full
                ? LevelChunkTicks.<Block>load(blocks, id -> BuiltInRegistries.BLOCK.getOptional(ResourceLocation.tryParse(id)), pos)
                : ProtoChunkTicks.<Block>load(blocks, id -> BuiltInRegistries.BLOCK.getOptional(ResourceLocation.tryParse(id)), pos);
        }
        ListTag fluids = chunk.getList("fluid_ticks", Tag.TAG_COMPOUND);
        if (!fluids.isEmpty()) {
            b.fluidTickList = fluids;
            b.fluidTicks = full
                ? LevelChunkTicks.<Fluid>load(fluids, id -> BuiltInRegistries.FLUID.getOptional(ResourceLocation.tryParse(id)), pos)
                : ProtoChunkTicks.<Fluid>load(fluids, id -> BuiltInRegistries.FLUID.getOptional(ResourceLocation.tryParse(id)), pos);
        }
    }

    // ---- server thread, inside ChunkSerializer.read

    /** At the start of {@code read}. Makes the bundle prepared for this tag the current one, or clears it if there is none. */
    public static void begin(CompoundTag chunk) {
        CURRENT.set(enabled() ? READY.remove(chunk) : null);
    }

    /** At the end of {@code read}. */
    public static void end() {
        CURRENT.set(null);
    }

    /** For tests: the bundle prepared for {@code chunk}, made current. */
    static void beginForTest(CompoundTag chunk) {
        CURRENT.set(READY.remove(chunk));
    }

    @Nullable
    public static Bundle current() {
        return CURRENT.get();
    }

    /** The decoded container prepared from {@code tag} (a {@code block_states} or {@code biomes} compound), or null. */
    @Nullable
    public static Object takeDecoded(Bundle b, CompoundTag tag) {
        int n = b.count;
        for (int k = 0; k < n; k++) {
            int i = b.cursor + k;
            if (i >= n) {
                i -= n;
            }
            if (b.stateTags[i] == tag) {
                b.cursor = i;
                return take(b, i, true);
            }
            if (b.biomeTags[i] == tag) {
                b.cursor = i;
                return take(b, i, false);
            }
        }
        return null;
    }

    @Nullable
    private static Object take(Bundle b, int i, boolean states) {
        CompoundTag[] tags = states ? b.stateTags : b.biomeTags;
        Object[] values = states ? b.states : b.biomes;
        Object value = values[i];
        CompoundTag tag = tags[i];
        tags[i] = null;
        values[i] = null;
        if (b.checked) {
            CompoundTag copy = (states ? b.stateCopies : b.biomeCopies)[i];
            if (!tag.equals(copy)) {
                CHANGED.increment();
                // The section built from this container does not match what read will build either.
                b.sections[i] = null;
                return null;
            }
        }
        TAKEN.increment();
        return value;
    }

    /** The section prepared for these two containers, or null. */
    @Nullable
    public static LevelChunkSection takeSection(Bundle b, PalettedContainer<BlockState> states, PalettedContainerRO<Holder<Biome>> biomes) {
        int i = b.cursor;
        LevelChunkSection s = b.sections[i];
        if (s == null || s.getStates() != states || s.getBiomes() != biomes) {
            return null;
        }
        b.sections[i] = null;
        TAKEN.increment();
        return s;
    }

    /** The ticks prepared from {@code list} for {@code pos}, of the kind the server thread is about to load, or null. */
    @Nullable
    public static Object takeTicks(Bundle b, ListTag list, ChunkPos pos, Class<?> kind) {
        Object ticks;
        if (list == b.blockTickList) {
            ticks = b.blockTicks;
            b.blockTickList = null;
            b.blockTicks = null;
        } else if (list == b.fluidTickList) {
            ticks = b.fluidTicks;
            b.fluidTickList = null;
            b.fluidTicks = null;
        } else {
            return null;
        }
        if (ticks == null || !kind.isInstance(ticks) || !pos.equals(b.tickPos)) {
            return null;
        }
        TAKEN.increment();
        return ticks;
    }

    /** Whether {@code primeHeightmaps(chunk, types)} can be skipped: there are no heightmaps to prime and no mod changed it. */
    public static boolean skipPrime(Set<?> types) {
        if (types.isEmpty() && enabled() && PRIME.allowed()) {
            PRIMES_SKIPPED.increment();
            return true;
        }
        return false;
    }

    // ---- verify switch. Compares a taken result with what vanilla's call makes from the same input.

    /** Same contents. Both encode to equal tags with the codec that decoded them, including palette order and bit width. */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static boolean sameDecoded(Codec codec, Object a, Object b) {
        DataResult<Tag> ea = codec.encodeStart(NbtOps.INSTANCE, a);
        DataResult<Tag> eb = codec.encodeStart(NbtOps.INSTANCE, b);
        return ea.result().isPresent() && ea.result().equals(eb.result());
    }

    /** Same block counts. These counts are the only state a new section derives from its containers. */
    public static boolean sameSection(LevelChunkSection a, LevelChunkSection b) {
        LevelChunkSectionCounts ca = (LevelChunkSectionCounts) a;
        LevelChunkSectionCounts cb = (LevelChunkSectionCounts) b;
        return ca.ga2$nonEmptyBlockCount() == cb.ga2$nonEmptyBlockCount() && ca.ga2$tickingBlockCount() == cb.ga2$tickingBlockCount()
            && ca.ga2$tickingFluidCount() == cb.ga2$tickingFluidCount();
    }

    /** Implemented by {@code LevelChunkSectionAccessor}. */
    public interface LevelChunkSectionCounts {
        short ga2$nonEmptyBlockCount();

        short ga2$tickingBlockCount();

        short ga2$tickingFluidCount();
    }

    /** Statistics line for {@code /ga2 steps stats}. */
    public static String stats() {
        return "loading: chunks parsed on the pool " + ParallelRead.PARSED.sum() + " (on the IO thread " + ParallelRead.ON_IO_THREAD.sum()
            + "), prepared " + CHUNKS.sum() + " (palettes only, checked: " + CHECKED_CHUNKS.sum() + "), parts taken " + TAKEN.sum()
            + ", tags changed since " + CHANGED.sum() + ", empty heightmap primes skipped " + PRIMES_SKIPPED.sum();
    }
}
