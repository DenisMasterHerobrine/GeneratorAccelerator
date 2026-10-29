package dev.denismasterherobrine.generatoraccelerator2.storage;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.LongStream;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;

/**
 * Builds directly the two section tags that {@code SerializableChunkData.write} encodes through codecs: {@code block_states}
 * and {@code biomes}. The codec packs the container and writes {@code {palette: [entries], data: [longs]}}. This class
 * packs the container the same way ({@code pack}, so with {@link FastPack}) and builds the same compound with the same
 * keys. Each block state entry is a copy of what vanilla's state codec encodes for that state. That encoding is computed once
 * per state through the codec itself. Each biome entry is the biome's registry name. Anything the shortcut does not handle goes
 * back to the codec, e.g. a holder without a key or a codec error. The tag is therefore identical either way.
 *
 * <p>The codecs of {@code PalettedContainerFactory} are wrapped once, when the factory is made ({@link #states}, {@link #biomes}).
 * A wrapped codec encodes with this class and decodes with {@link FastPalettes}. Every other use goes to vanilla's codec.
 *
 * <p>{@code -Dgeneratoraccelerator2.sectionCodec=false} turns it off.
 */
public final class SectionCodec {
    private SectionCodec() {}

    /** Not final so the exactness test can compare both ways. */
    public static boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.sectionCodec"));

    private static final Map<BlockState, CompoundTag> STATE_TAGS = new ConcurrentHashMap<>();
    /**
     * The name tag of each biome, made once per key. A {@code StringTag} is immutable, and its {@code copy} returns itself. One
     * tag therefore serves every palette that names the biome, as the codec's freshly built tag would.
     */
    private static final Map<ResourceKey<Biome>, StringTag> BIOME_TAGS = new ConcurrentHashMap<>();

    public static boolean enabled() {
        return ENABLED && StorageConfig.sectionCodec() && HookTargets.allowed(HookTargets.STORAGE_SECTION_CODEC);
    }

    /** The factory's block state codec with the fast encode and decode in front. */
    public static Codec<PalettedContainer<BlockState>> states(Codec<PalettedContainer<BlockState>> vanilla, Codec<BlockState> element,
                                                              Strategy<BlockState> strategy) {
        FastPalettes.Entries<BlockState> entries = new FastPalettes.Entries<>(element);
        return new Fast<>(vanilla, tag -> FastPalettes.decode(tag, entries, strategy), states -> blockStates(states, strategy));
    }

    /** The factory's biome codec with the fast encode and decode in front. */
    public static Codec<PalettedContainerRO<Holder<Biome>>> biomes(Codec<PalettedContainerRO<Holder<Biome>>> vanilla, Codec<Holder<Biome>> element,
                                                                   Strategy<Holder<Biome>> strategy) {
        FastPalettes.Entries<Holder<Biome>> entries = new FastPalettes.Entries<>(element);
        return new Fast<>(vanilla, tag -> FastPalettes.decode(tag, entries, strategy), biomes -> biomes(biomes, strategy));
    }

    /** A section codec that tries the shortcuts first. A null answer falls back to {@code vanilla}. */
    private record Fast<C>(Codec<C> vanilla, Function<CompoundTag, ? extends C> fastDecode,
                           Function<C, Tag> fastEncode) implements Codec<C> {
        @Override
        public <O> DataResult<Pair<C, O>> decode(DynamicOps<O> ops, O input) {
            if (ops == NbtOps.INSTANCE && input instanceof CompoundTag tag && FastPalettes.enabled()) {
                C value = fastDecode.apply(tag);
                if (value != null) {
                    // the record codec pairs its value with the whole input, as here
                    return DataResult.success(Pair.of(value, input));
                }
            }
            return vanilla.decode(ops, input);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <O> DataResult<O> encode(C input, DynamicOps<O> ops, O prefix) {
            // encodeStart passes the empty value. Merging into another prefix stays with the codec.
            if (ops == NbtOps.INSTANCE && prefix == ops.empty() && enabled()) {
                Tag tag = fastEncode.apply(input);
                if (tag != null) {
                    return DataResult.success((O) tag);
                }
            }
            return vanilla.encode(input, ops, prefix);
        }

        @Override
        public String toString() {
            return "GA2[" + vanilla + "]";
        }
    }

    /** {@code block_states} of a section, or null to use the codec. */
    @Nullable
    public static Tag blockStates(PalettedContainer<BlockState> states, Strategy<BlockState> strategy) {
        FastPack.Raw<BlockState> raw = FastPack.enabled() ? packRaw(states, strategy) : null;
        if (raw != null) {
            ListTag palette = statePalette(raw.entries());
            return palette == null ? null : container(palette, raw.data());
        }
        PalettedContainerRO.PackedData<BlockState> packed = states.pack(strategy);
        ListTag palette = statePalette(packed.paletteEntries());
        return palette == null ? null : container(palette, packed.storage());
    }

    /** {@code biomes} of a section, or null to use the codec. */
    @Nullable
    public static Tag biomes(PalettedContainerRO<Holder<Biome>> biomes, Strategy<Holder<Biome>> strategy) {
        FastPack.Raw<Holder<Biome>> raw = FastPack.enabled() && biomes instanceof PalettedContainer<Holder<Biome>> container
            ? packRaw(container, strategy) : null;
        if (raw != null) {
            ListTag palette = biomePalette(raw.entries());
            return palette == null ? null : container(palette, raw.data());
        }
        PalettedContainerRO.PackedData<Holder<Biome>> packed = biomes.pack(strategy);
        ListTag palette = biomePalette(packed.paletteEntries());
        return palette == null ? null : container(palette, packed.storage());
    }

    /** {@link FastPack#packRaw} under the container's lock, as {@code pack} takes it. */
    @Nullable
    private static <T> FastPack.Raw<T> packRaw(PalettedContainer<T> container, Strategy<T> strategy) {
        container.acquire();
        try {
            return FastPack.packRaw(container.data, strategy);
        } finally {
            container.release();
        }
    }

    @Nullable
    private static ListTag statePalette(List<BlockState> entries) {
        ListTag palette = new ListTag();
        for (BlockState state : entries) {
            CompoundTag tag = STATE_TAGS.get(state);
            if (tag == null) {
                Optional<Tag> encoded = BlockState.CODEC.encodeStart(NbtOps.INSTANCE, state).result();
                if (encoded.isEmpty() || !(encoded.get() instanceof CompoundTag compound)) {
                    return null;
                }
                tag = compound;
                STATE_TAGS.put(state, tag);
            }
            palette.add(tag.copy());
        }
        return palette;
    }

    @Nullable
    private static ListTag biomePalette(List<Holder<Biome>> entries) {
        ListTag palette = new ListTag();
        for (Holder<Biome> biome : entries) {
            // Registry.holderByNameCodec encodes a holder as its key's location. A holder without a key is an error.
            Optional<ResourceKey<Biome>> key = biome.unwrapKey();
            if (key.isEmpty()) {
                return null;
            }
            ResourceKey<Biome> k = key.get();
            StringTag name = BIOME_TAGS.get(k);
            if (name == null) {
                name = StringTag.valueOf(k.identifier().toString());
                BIOME_TAGS.putIfAbsent(k, name);
            }
            palette.add(name);
        }
        return palette;
    }

    /** The codec's record: {@code palette} first, then {@code data} when there is storage. */
    private static CompoundTag container(ListTag palette, Optional<LongStream> storage) {
        return container(palette, storage.isPresent() ? storage.get().toArray() : null);
    }

    /** {@link #container(ListTag, Optional)} with the packed longs themselves, which the tag keeps. Null for no storage. */
    private static CompoundTag container(ListTag palette, @Nullable long[] storage) {
        CompoundTag out = new CompoundTag();
        out.put("palette", palette);
        if (storage != null) {
            out.put("data", new LongArrayTag(storage));
        }
        return out;
    }
}
