package dev.denismasterherobrine.generatoraccelerator2.storage;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.LongStream;
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
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;

/**
 * Builds directly the two section tags that {@code ChunkSerializer.write} encodes through codecs: {@code block_states}
 * and {@code biomes}. The codec packs the container and writes {@code {palette: [entries], data: [longs]}}. This class
 * packs the container the same way ({@code pack}, so with {@link FastPack}) and builds the same compound, with the same
 * keys in the same insertion order. Each block state entry is a copy of what vanilla's state codec encodes for that state.
 * That encoding is computed once per state through the codec itself and copied in its own key order. Each biome entry is
 * the biome's registry name. Anything the shortcut does not handle goes back to the codec, e.g. a holder without a key or
 * a codec error. The tag is therefore identical either way.
 *
 * <p>{@code -Dgeneratoraccelerator2.sectionCodec=false} turns it off.
 */
public final class SectionCodec {
    private SectionCodec() {}

    /** Not final so the exactness test can compare both ways. */
    public static boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.sectionCodec"));

    private static final Map<BlockState, TagRecipe> STATE_TAGS = new ConcurrentHashMap<>();

    public static boolean enabled() {
        return ENABLED && StorageConfig.sectionCodec() && HookTargets.allowed(HookTargets.STORAGE_SECTION_CODEC);
    }

    /** {@code block_states} of a section, or null to use the codec. */
    @Nullable
    public static Tag blockStates(PalettedContainer<BlockState> states) {
        FastPack.Raw<BlockState> raw = FastPack.enabled() ? packRaw(states, Block.BLOCK_STATE_REGISTRY, PalettedContainer.Strategy.SECTION_STATES) : null;
        return raw != null ? blockStates(raw) : blockStates(states.pack(Block.BLOCK_STATE_REGISTRY, PalettedContainer.Strategy.SECTION_STATES));
    }

    /**
     * {@link FastPack#packRaw} under the container's lock, as {@code pack} takes it. The longs stay the array the tag keeps, without the
     * stream {@code pack}'s result wraps them in.
     */
    @Nullable
    private static <T> FastPack.Raw<T> packRaw(PalettedContainer<T> container, IdMap<T> registry, PalettedContainer.Strategy strategy) {
        container.acquire();
        try {
            return FastPack.packRaw(container.data, registry, strategy);
        } finally {
            container.release();
        }
    }

    /** {@code block_states} from a container packed for saving, or null to use the codec. {@code SavePrep} packs a copy. */
    @Nullable
    public static Tag blockStates(@Nullable PalettedContainerRO.PackedData<BlockState> packed) {
        if (packed == null) {
            return null;
        }
        ListTag palette = statePalette(packed.paletteEntries());
        return palette == null ? null : container(palette, packed.storage());
    }

    /** {@link #blockStates(PalettedContainerRO.PackedData)} from {@link FastPack#packRaw}: its longs go into the tag as they are. */
    @Nullable
    public static Tag blockStates(@Nullable FastPack.Raw<BlockState> packed) {
        if (packed == null) {
            return null;
        }
        ListTag palette = statePalette(packed.entries());
        return palette == null ? null : container(palette, packed.data());
    }

    @Nullable
    private static ListTag statePalette(List<BlockState> entries) {
        ListTag palette = new ListTag();
        for (BlockState state : entries) {
            TagRecipe tag = STATE_TAGS.get(state);
            if (tag == null) {
                Optional<Tag> encoded = BlockState.CODEC.encodeStart(NbtOps.INSTANCE, state).result();
                if (encoded.isEmpty() || !(encoded.get() instanceof CompoundTag compound)) {
                    return null;
                }
                tag = TagRecipe.of(compound);
                STATE_TAGS.put(state, tag);
            }
            palette.add(tag.make());
        }
        return palette;
    }

    /** {@code biomes} of a section, or null to use the codec. */
    @Nullable
    public static Tag biomes(PalettedContainerRO<Holder<Biome>> biomes, Registry<Biome> registry) {
        FastPack.Raw<Holder<Biome>> raw = FastPack.enabled() && biomes instanceof PalettedContainer<Holder<Biome>> container
            ? packRaw(container, registry.asHolderIdMap(), PalettedContainer.Strategy.SECTION_BIOMES) : null;
        return raw != null ? biomes(raw) : biomes(biomes.pack(registry.asHolderIdMap(), PalettedContainer.Strategy.SECTION_BIOMES));
    }

    /** {@code biomes} from a container packed for saving, or null to use the codec. */
    @Nullable
    public static Tag biomes(@Nullable PalettedContainerRO.PackedData<Holder<Biome>> packed) {
        if (packed == null) {
            return null;
        }
        ListTag palette = biomePalette(packed.paletteEntries());
        return palette == null ? null : container(palette, packed.storage());
    }

    /** {@link #biomes(PalettedContainerRO.PackedData)} from {@link FastPack#packRaw}: its longs go into the tag as they are. */
    @Nullable
    public static Tag biomes(@Nullable FastPack.Raw<Holder<Biome>> packed) {
        if (packed == null) {
            return null;
        }
        ListTag palette = biomePalette(packed.entries());
        return palette == null ? null : container(palette, packed.data());
    }

    /**
     * The name tag of each biome, made once per key. A {@code StringTag} is immutable, and its {@code copy} returns itself. One
     * tag therefore serves every palette that names the biome, as the codec's freshly built tag would.
     */
    private static final Map<ResourceKey<Biome>, StringTag> BIOME_TAGS = new ConcurrentHashMap<>();

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
                name = StringTag.valueOf(k.location().toString());
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

    /**
     * A compound's keys and values in its iteration order. {@link #make} builds a copy that inserts the keys in that order, so the copy
     * iterates the same way. Unlike copying the compound itself, it walks no hash map and looks up no key.
     */
    private record TagRecipe(String[] keys, Object[] values) {
        static TagRecipe of(CompoundTag source) {
            String[] keys = source.getAllKeys().toArray(new String[0]);
            Object[] values = new Object[keys.length];
            for (int i = 0; i < keys.length; i++) {
                Tag value = source.get(keys[i]);
                values[i] = value instanceof CompoundTag compound ? of(compound) : value;
            }
            return new TagRecipe(keys, values);
        }

        CompoundTag make() {
            CompoundTag out = new CompoundTag();
            for (int i = 0; i < keys.length; i++) {
                Object value = values[i];
                out.put(keys[i], value instanceof TagRecipe recipe ? recipe.make() : ((Tag) value).copy());
            }
            return out;
        }
    }
}
