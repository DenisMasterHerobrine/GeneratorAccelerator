package dev.denismasterherobrine.generatoraccelerator2.storage;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;
import net.minecraft.core.IdMap;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;

/**
 * Vanilla's {@code PalettedContainer.pack}. Every saved section runs it twice: for block states and for biomes.
 *
 * <p>Vanilla unpacks the storage and builds a fresh hash palette. It asks that palette for the id of each entry's value,
 * once per run of equal entries. It then packs the new ids at the bit width the new palette needs. The new palette lists
 * the values in the order they first occur in the section. This class does the same with an array from old ids to new
 * ids. Each old id is looked up once per section, and the first occurrences give the same order. The bit width comes from
 * the same strategy method, and the same loop packs the ids. Old ids map to distinct values, so the result is identical.
 * A single, linear or hash palette never holds a value twice. A global palette is keyed by value instead.
 *
 * <p>{@code -Dgeneratoraccelerator2.fastPack=false} turns it off.
 */
public final class FastPack {
    private FastPack() {}

    /** Not final so the exactness test can compare both ways. */
    public static boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.fastPack"));

    /** Containers packed here (for tests and stats). */
    public static final LongAdder PACKED = new LongAdder();

    private static final int MAX_LOCAL_BITS = 8;
    private static final ThreadLocal<int[]> IDS = ThreadLocal.withInitial(() -> new int[4096]);
    private static final ThreadLocal<int[]> REMAP = ThreadLocal.withInitial(() -> new int[1 << MAX_LOCAL_BITS]);

    public static boolean enabled() {
        return ENABLED && StorageConfig.fastPack() && HookTargets.allowed(HookTargets.STORAGE_PACK);
    }

    /**
     * The values {@link #pack} wraps in vanilla's types: the new palette's entries and the packed longs. The longs are null at
     * zero bits, where vanilla has no data. Otherwise they are a fresh array the caller may keep. The section codec puts that
     * array in its tag without the stream's copy.
     */
    public record Raw<T>(List<T> entries, @Nullable long[] data) {
        public PalettedContainerRO.PackedData<T> packedData() {
            return new PalettedContainerRO.PackedData<>(entries, data == null ? Optional.empty() : Optional.of(Arrays.stream(data)));
        }
    }

    /**
     * @return the packed data vanilla would produce, or null when the container is in a shape this does not handle
     * (vanilla then packs it)
     */
    @Nullable
    public static <T> PalettedContainerRO.PackedData<T> pack(PalettedContainer.Data<T> data, IdMap<T> registry, PalettedContainer.Strategy strategy) {
        Raw<T> raw = packRaw(data, registry, strategy);
        return raw == null ? null : raw.packedData();
    }

    /** {@link #pack} before its result is wrapped in a stream ({@link Raw}), or null when vanilla packs it. */
    @Nullable
    public static <T> Raw<T> packRaw(PalettedContainer.Data<T> data, IdMap<T> registry, PalettedContainer.Strategy strategy) {
        int size = strategy.size();
        BitStorage storage = data.storage();
        if (storage.getSize() != size) {
            return null;
        }
        Palette<T> palette = data.palette();
        List<T> entries = new ArrayList<>();
        if (storage instanceof ZeroBitStorage) {
            // Every entry is id 0. That gives one palette value, and every new id is 0.
            T value = palette.valueFor(0);
            if (value == null) {
                return null;
            }
            entries.add(value);
            int newBits = strategy.calculateBitsForSerialization(registry, 1);
            PACKED.increment();
            return new Raw<>(entries, newBits != 0 ? new long[(size + 64 / newBits - 1) / (64 / newBits)] : null);
        }
        int[] ids = size <= 4096 ? IDS.get() : new int[size];
        int bits = storage.getBits();
        if (palette instanceof GlobalPalette || bits > MAX_LOCAL_BITS) {
            storage.unpack(ids);
            if (!remapByValue(ids, size, palette, entries)) {
                return null;
            }
        } else if (storage instanceof SimpleBitStorage simple) {
            if (!remapPacked(simple.getRaw(), bits, ids, size, palette, entries)) {
                return null;
            }
        } else {
            storage.unpack(ids);
            if (!remapUnpacked(ids, size, bits, palette, entries)) {
                return null;
            }
        }
        int newBits = strategy.calculateBitsForSerialization(registry, entries.size());
        PACKED.increment();
        return new Raw<>(entries, newBits != 0 ? packValues(ids, size, newBits) : null);
    }

    /** Unpacks {@code raw} (SimpleBitStorage layout) and remaps every entry in the same pass. */
    private static <T> boolean remapPacked(long[] raw, int bits, int[] ids, int size, Palette<T> palette, List<T> entries) {
        int[] remap = REMAP.get();
        Arrays.fill(remap, 0, 1 << bits, -1);
        int perLong = 64 / bits;
        long mask = (1L << bits) - 1L;
        int lastOld = -1;
        int lastNew = -1;
        int k = 0;
        for (int w = 0; w < raw.length && k < size; w++) {
            long word = raw[w];
            int end = Math.min(size, k + perLong);
            for (; k < end; k++) {
                int old = (int) (word & mask);
                word >>= bits;
                if (old != lastOld) {
                    lastOld = old;
                    int id = remap[old];
                    if (id < 0) {
                        T value = palette.valueFor(old);
                        if (value == null) {
                            return false;
                        }
                        id = entries.size();
                        remap[old] = id;
                        entries.add(value);
                    }
                    lastNew = id;
                }
                ids[k] = lastNew;
            }
        }
        return k == size;
    }

    private static <T> boolean remapUnpacked(int[] ids, int size, int bits, Palette<T> palette, List<T> entries) {
        int[] remap = REMAP.get();
        Arrays.fill(remap, 0, 1 << bits, -1);
        int lastOld = -1;
        int lastNew = -1;
        for (int k = 0; k < size; k++) {
            int old = ids[k];
            if (old != lastOld) {
                lastOld = old;
                if (old < 0 || old >= remap.length) {
                    return false;
                }
                int id = remap[old];
                if (id < 0) {
                    T value = palette.valueFor(old);
                    if (value == null) {
                        return false;
                    }
                    id = entries.size();
                    remap[old] = id;
                    entries.add(value);
                }
                lastNew = id;
            }
            ids[k] = lastNew;
        }
        return true;
    }

    /** Keyed by value identity, as vanilla's hash palette is. */
    private static <T> boolean remapByValue(int[] ids, int size, Palette<T> palette, List<T> entries) {
        Reference2IntOpenHashMap<T> byValue = new Reference2IntOpenHashMap<>();
        byValue.defaultReturnValue(-1);
        int lastOld = Integer.MIN_VALUE;
        int lastNew = -1;
        for (int k = 0; k < size; k++) {
            int old = ids[k];
            if (old != lastOld) {
                lastOld = old;
                T value = palette.valueFor(old);
                if (value == null) {
                    return false;
                }
                int id = byValue.getInt(value);
                if (id < 0) {
                    id = entries.size();
                    byValue.put(value, id);
                    entries.add(value);
                }
                lastNew = id;
            }
            ids[k] = lastNew;
        }
        return true;
    }

    /** {@code new SimpleBitStorage(bits, size, values).getRaw()}, the same loop. */
    static long[] packValues(int[] values, int size, int bits) {
        int perLong = 64 / bits;
        long mask = (1L << bits) - 1L;
        long[] data = new long[(size + perLong - 1) / perLong];
        int j = 0;
        int i;
        for (i = 0; i <= size - perLong; i += perLong) {
            long word = 0L;
            for (int t = perLong - 1; t >= 0; t--) {
                word <<= bits;
                word |= (long) values[i + t] & mask;
            }
            data[j++] = word;
        }
        int rest = size - i;
        if (rest > 0) {
            long word = 0L;
            for (int t = rest - 1; t >= 0; t--) {
                word <<= bits;
                word |= (long) values[i + t] & mask;
            }
            data[j] = word;
        }
        return data;
    }
}
