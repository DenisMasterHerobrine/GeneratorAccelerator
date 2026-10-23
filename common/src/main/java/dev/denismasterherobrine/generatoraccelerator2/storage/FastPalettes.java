package dev.denismasterherobrine.generatoraccelerator2.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.mixin.storage.PalettedContainerUnpackInvoker;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;

/**
 * A loaded section's palettes without the codecs' records and lists. The section codecs ({@code PalettedContainer.codecRW} and
 * {@code codecRO}) read the {@code palette} list entry by entry with the element codec, read {@code data} as a long stream, and
 * hand both to {@code PalettedContainer.unpack}. Through DataFixerUpper that costs a result object, a pair and a stream per
 * entry, about a third of a loaded chunk's garbage. Here each entry is the element codec's own answer for an equal entry,
 * kept from an earlier decode, and the container comes from the same {@code unpack} with the same palette and data.
 *
 * <p>Exact: an entry's answer depends only on the entry's tag, since the block and biome registries do not change while
 * they are in use. Only answers without errors are kept. Anything this class does not cover returns null, and the caller
 * decodes with the codec as before. That covers an entry not yet known whose decode has an error, a palette that is not a
 * list of the expected tags, and data that is not a long array. Another mod's change to the codec builders, to
 * {@code unpack} or to {@code ChunkSerializer}'s section codecs turns this off ({@link HookTargets#STORAGE_FAST_PALETTES}).
 * {@code -Dgeneratoraccelerator2.fastPalettes=false} turns it off too. {@code verify.loadPrep} compares the containers
 * with vanilla's decode.
 */
public final class FastPalettes {
    private FastPalettes() {}

    static final boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.fastPalettes"));
    private static final HookTargets.Hook HOOK = HookTargets.hook(HookTargets.STORAGE_FAST_PALETTES);
    /** Entries kept at most per cache, a few hundred bytes each. Past that, entries not yet known decode with the codec. */
    private static final int MAX_ENTRIES = 16384;
    public static final LongAdder DECODED = new LongAdder();
    public static final LongAdder LEFT = new LongAdder();

    /** Block states by their palette entry. The keys are copies, so no caller's tag can change them. */
    private static final ConcurrentHashMap<CompoundTag, BlockState> STATES = new ConcurrentHashMap<>();
    /** Biomes by name, for one biome registry. A new server's registry gets its own. */
    private record BiomeNames(Registry<Biome> registry, ConcurrentHashMap<String, Holder<Biome>> names) {}

    @Nullable
    private static volatile BiomeNames biomeNames;

    static boolean enabled() {
        return ENABLED && HOOK.allowed();
    }

    /** {@code block_states} as {@code ChunkSerializer}'s block state codec decodes it, or null when the codec must decode it. */
    @Nullable
    static PalettedContainer<BlockState> states(CompoundTag tag) {
        if (!(tag.get("palette") instanceof ListTag list) || list.isEmpty() || list.getElementType() != Tag.TAG_COMPOUND) {
            return left();
        }
        List<BlockState> entries = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = (CompoundTag) list.get(i);
            BlockState state = STATES.get(entry);
            if (state == null) {
                state = element(BlockState.CODEC, entry);
                if (state == null) {
                    return left();
                }
                if (STATES.size() < MAX_ENTRIES) {
                    STATES.putIfAbsent(entry.copy(), state);
                }
            }
            entries.add(state);
        }
        return build(Block.BLOCK_STATE_REGISTRY, PalettedContainer.Strategy.SECTION_STATES, entries, tag);
    }

    /** {@code biomes} as {@code ChunkSerializer.makeBiomeCodec(registry)} decodes it, or null when the codec must decode it. */
    @Nullable
    @SuppressWarnings({ "unchecked", "rawtypes" })
    static PalettedContainerRO<Holder<Biome>> biomes(Registry<Biome> registry, CompoundTag tag) {
        if (!(tag.get("palette") instanceof ListTag list) || list.isEmpty() || list.getElementType() != Tag.TAG_STRING) {
            return left();
        }
        BiomeNames names = biomeNames;
        if (names == null || names.registry() != registry) {
            // Two threads may each make one. Either map holds answers of this registry only.
            biomeNames = names = new BiomeNames(registry, new ConcurrentHashMap<>());
        }
        ConcurrentHashMap<String, Holder<Biome>> known = names.names();
        Codec<Holder<Biome>> codec = null;
        List<Holder<Biome>> entries = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            StringTag entry = (StringTag) list.get(i);
            String name = entry.getAsString();
            Holder<Biome> biome = known.get(name);
            if (biome == null) {
                if (codec == null) {
                    codec = registry.holderByNameCodec();
                }
                biome = element(codec, entry);
                if (biome == null) {
                    return left();
                }
                if (known.size() < MAX_ENTRIES) {
                    known.putIfAbsent(name, biome);
                }
            }
            entries.add(biome);
        }
        return (PalettedContainerRO) build(registry.asHolderIdMap(), PalettedContainer.Strategy.SECTION_BIOMES, entries, tag);
    }

    /** The element codec's answer for one entry, or null if its decode has an error (the list codec would give a partial result). */
    @Nullable
    private static <T> T element(Codec<T> codec, Tag entry) {
        DataResult<T> r = codec.parse(NbtOps.INSTANCE, entry);
        Optional<T> value = r.result();
        return value.isPresent() && r.error().isEmpty() ? value.get() : null;
    }

    /** {@code unpack} with the palette and the data as the codec reads them: a long array, or none when {@code data} is missing. */
    @Nullable
    private static <T> PalettedContainer<T> build(IdMap<T> registry, PalettedContainer.Strategy strategy, List<T> entries, CompoundTag tag) {
        Tag data = tag.get("data");
        Optional<java.util.stream.LongStream> storage;
        if (data == null) {
            storage = Optional.empty();
        } else if (data instanceof LongArrayTag longs) {
            storage = Optional.of(Arrays.stream(longs.getAsLongArray()));
        } else {
            return left();
        }
        DataResult<PalettedContainer<T>> r = PalettedContainerUnpackInvoker.ga2$unpack(registry, strategy, new PalettedContainerRO.PackedData<>(entries, storage));
        Optional<PalettedContainer<T>> value = r.result();
        if (value.isEmpty() || r.error().isPresent()) {
            return left();
        }
        DECODED.increment();
        return value.get();
    }

    @Nullable
    private static <T> T left() {
        LEFT.increment();
        return null;
    }
}
