package dev.denismasterherobrine.generatoraccelerator2.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.LongStream;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;

/**
 * A loaded section's palettes without the codecs' records and lists. The section codecs ({@code PalettedContainer.codecRW} and
 * {@code codecRO}, made by {@code PalettedContainerFactory}) read the {@code palette} list entry by entry with the element codec,
 * read {@code data} as a long stream, and hand both to {@code PalettedContainer.unpack}. Through DataFixerUpper that costs a result
 * object, a pair and a stream per entry, about a third of a loaded chunk's garbage. Here each entry is the element codec's own
 * answer for an equal entry, kept from an earlier decode, and the container comes from the same {@code unpack} with the same
 * palette and data. Vanilla decodes loaded chunks on the worker pool ({@code SerializableChunkData.parse}), so this saves worker
 * time and garbage.
 *
 * <p>Exact: an entry's answer depends only on the entry's tag, since the block and biome registries do not change while
 * they are in use. Only answers without errors are kept. Anything this class does not cover returns null, and the caller
 * decodes with the codec as before. That covers an entry not yet known whose decode has an error, a palette that is not a
 * list of the expected tags, and data that is not a long array. Another mod's change to the codec builders, to
 * {@code unpack} or to {@code PalettedContainerFactory} turns this off ({@link HookTargets#STORAGE_FAST_PALETTES}).
 * {@code -Dgeneratoraccelerator2.fastPalettes=false} turns it off too.
 */
public final class FastPalettes {
    private FastPalettes() {}

    static final boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.fastPalettes"));
    private static final HookTargets.Hook HOOK = HookTargets.hook(HookTargets.STORAGE_FAST_PALETTES);
    /** Entries kept at most per cache, a few hundred bytes each. Past that, entries not yet known decode with the codec. */
    private static final int MAX_ENTRIES = 16384;
    public static final LongAdder DECODED = new LongAdder();
    public static final LongAdder LEFT = new LongAdder();

    static boolean enabled() {
        return ENABLED && HOOK.allowed();
    }

    /**
     * The element answers of one section codec. Block state entries are compounds, keyed by copies so no caller's tag can change
     * them. Biome entries are names. Each codec the factory makes has its own, so one cache serves one biome registry.
     */
    public static final class Entries<T> {
        final Codec<T> element;
        final ConcurrentHashMap<Object, T> known = new ConcurrentHashMap<>();

        public Entries(Codec<T> element) {
            this.element = element;
        }
    }

    /** A section's {@code block_states} or {@code biomes} as the section codec decodes it, or null when the codec must decode it. */
    @Nullable
    public static <T> PalettedContainer<T> decode(CompoundTag tag, Entries<T> entries, Strategy<T> strategy) {
        if (!(tag.get("palette") instanceof ListTag list) || list.isEmpty()) {
            return left();
        }
        List<T> values = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Tag entry = list.get(i);
            Object key;
            if (entry instanceof StringTag name) {
                key = name.value();
            } else if (entry instanceof CompoundTag) {
                key = entry;
            } else {
                return left();
            }
            T value = entries.known.get(key);
            if (value == null) {
                value = element(entries.element, entry);
                if (value == null) {
                    return left();
                }
                if (entries.known.size() < MAX_ENTRIES) {
                    entries.known.putIfAbsent(key instanceof CompoundTag compound ? compound.copy() : key, value);
                }
            }
            values.add(value);
        }
        return build(strategy, values, tag);
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
    private static <T> PalettedContainer<T> build(Strategy<T> strategy, List<T> entries, CompoundTag tag) {
        Tag data = tag.get("data");
        Optional<LongStream> storage;
        if (data == null) {
            storage = Optional.empty();
        } else if (data instanceof LongArrayTag longs) {
            storage = Optional.of(Arrays.stream(longs.getAsLongArray()));
        } else {
            return left();
        }
        DataResult<PalettedContainer<T>> r = PalettedContainer.unpack(strategy, new PalettedContainerRO.PackedData<>(entries, storage));
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
