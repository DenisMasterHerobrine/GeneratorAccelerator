package dev.denismasterherobrine.generatoraccelerator2.storage;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;

/**
 * Vanilla's {@code PalettedContainer.pack}. Every saved section runs it twice: for block states and for biomes.
 *
 * <p>Vanilla unpacks the storage and builds a fresh hash palette. It asks that palette for the id of each entry's value,
 * once per run of equal entries. It then packs the new ids at the bit width the new palette needs. The new palette lists
 * the values in the order they first occur in the section. This class does the same with an array from old ids to new
 * ids. Each old id is looked up once per section, and the first occurrences give the same order. The bit width comes from
 * the same strategy configuration, and the same loop packs the ids. Old ids map to distinct values, so the result is identical.
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
     * The values {@link #pack} wraps in vanilla's types: the new palette's entries, the packed longs and their bit width. The
     * longs are null at zero bits, where vanilla has no data. Otherwise they are a fresh array the caller may keep. The section
     * codec puts that array in its tag without the stream's copy.
     */
    public record Raw<T>(List<T> entries, @Nullable long[] data, int bits) {
        public PalettedContainerRO.PackedData<T> packedData() {
            return new PalettedContainerRO.PackedData<>(entries, data == null ? Optional.empty() : Optional.of(Arrays.stream(data)), bits);
        }
    }

    /**
     * @return the packed data vanilla would produce, or null when the container is in a shape this does not handle
     * (vanilla then packs it)
     */
    @Nullable
    public static <T> PalettedContainerRO.PackedData<T> pack(PalettedContainer.Data<T> data, Strategy<T> strategy) {
        Raw<T> raw = packRaw(data, strategy);
        return raw == null ? null : raw.packedData();
    }

    /** {@link #pack} before its result is wrapped in a stream ({@link Raw}), or null when vanilla packs it. */
    @Nullable
    public static <T> Raw<T> packRaw(PalettedContainer.Data<T> data, Strategy<T> strategy) {
        int size = strategy.entryCount();
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
            int newBits = strategy.getConfigurationForPaletteSize(1).bitsInStorage();
            PACKED.increment();
            return new Raw<>(entries, newBits != 0 ? new long[(size + 64 / newBits - 1) / (64 / newBits)] : null, newBits);
        }
        int bits = storage.getBits();
        if (!(palette instanceof GlobalPalette) && bits <= MAX_LOCAL_BITS && storage instanceof SimpleBitStorage simple) {
            return packLocal(simple.getRaw(), bits, size, palette, strategy);
        }
        int[] ids = size <= 4096 ? IDS.get() : new int[size];
        if (palette instanceof GlobalPalette || bits > MAX_LOCAL_BITS) {
            storage.unpack(ids);
            if (!remapByValue(ids, size, palette, entries)) {
                return null;
            }
        } else {
            storage.unpack(ids);
            if (!remapUnpacked(ids, size, bits, palette, entries)) {
                return null;
            }
        }
        int newBits = strategy.getConfigurationForPaletteSize(entries.size()).bitsInStorage();
        PACKED.increment();
        return new Raw<>(entries, newBits != 0 ? packValues(ids, size, newBits) : null, newBits);
    }

    /**
     * A local palette's container straight from its storage's longs ({@code raw}, SimpleBitStorage layout). The first pass maps each
     * old id to its new id in first-occurrence order and writes nothing else. When that order is the palette's own and the width
     * stays, the new longs are the old ones ({@link #cleanCopy}). Otherwise the second pass packs the new ids straight from the old
     * longs ({@link #repack}). Either way the longs equal vanilla's. Returns null when vanilla packs it.
     */
    @Nullable
    private static <T> Raw<T> packLocal(long[] raw, int bits, int size, Palette<T> palette, Strategy<T> strategy) {
        int[] remap = REMAP.get();
        Arrays.fill(remap, 0, 1 << bits, -1);
        // the new palette holds at most the old one's values, so the list never grows
        List<T> entries = new ArrayList<>(Math.max(1, Math.min(palette.getSize(), 1 << bits)));
        int perLong = 64 / bits;
        long mask = (1L << bits) - 1L;
        boolean identity = true;
        int lastOld = -1;
        int k = 0;
        for (int w = 0; w < raw.length && k < size; w++) {
            long word = raw[w];
            int end = Math.min(size, k + perLong);
            for (; k < end; k++) {
                int old = (int) (word & mask);
                word >>>= bits;
                if (old != lastOld) {
                    lastOld = old;
                    if (remap[old] < 0) {
                        T value = palette.valueFor(old);
                        if (value == null) {
                            return null;
                        }
                        int id = entries.size();
                        identity &= id == old;
                        remap[old] = id;
                        entries.add(value);
                    }
                }
            }
        }
        if (k != size) {
            return null;
        }
        int newBits = strategy.getConfigurationForPaletteSize(entries.size()).bitsInStorage();
        PACKED.increment();
        if (newBits == 0) {
            return new Raw<>(entries, null, 0);
        }
        long[] data = identity && newBits == bits ? cleanCopy(raw, bits, size) : repack(raw, bits, size, remap, newBits);
        return new Raw<>(entries, data, newBits);
    }

    /**
     * {@code raw}'s longs as vanilla would pack the same ids: a copy with the bits past each long's last entry cleared. A storage made
     * from saved longs keeps whatever those bits held, and vanilla's packing writes zeros there.
     */
    static long[] cleanCopy(long[] raw, int bits, int size) {
        int perLong = 64 / bits;
        int longs = (size + perLong - 1) / perLong;
        long[] out = Arrays.copyOf(raw, longs);
        int used = perLong * bits;
        if (used < 64) {
            long keep = (1L << used) - 1L;
            for (int i = 0; i < longs; i++) {
                out[i] &= keep;
            }
        }
        int rest = size - (longs - 1) * perLong;
        if (rest < perLong) {
            out[longs - 1] &= (1L << rest * bits) - 1L;
        }
        return out;
    }

    /**
     * The new ids of {@code raw}'s entries ({@code remap}, which maps every old id present) packed at {@code newBits}, as
     * {@link #packValues} packs them. Reads the old longs once and writes the new ones, without an id per entry in between.
     */
    static long[] repack(long[] raw, int bits, int size, int[] remap, int newBits) {
        int perLong = 64 / bits;
        int newPerLong = 64 / newBits;
        long[] out = new long[(size + newPerLong - 1) / newPerLong];
        if (bits == newBits && (bits == 4 || bits == 8) && size % perLong == 0) {
            return repackBytes(raw, bits, size / perLong, remap, out);
        }
        long mask = (1L << bits) - 1L;
        int o = 0;
        int filled = 0;
        int shift = 0;
        long word = 0L;
        int k = 0;
        for (int w = 0; k < size; w++) {
            long in = raw[w];
            int end = Math.min(size, k + perLong);
            for (; k < end; k++) {
                word |= (long) remap[(int) (in & mask)] << shift;
                in >>>= bits;
                shift += newBits;
                if (++filled == newPerLong) {
                    out[o++] = word;
                    word = 0L;
                    shift = 0;
                    filled = 0;
                }
            }
        }
        if (filled > 0) {
            out[o] = word;
        }
        return out;
    }

    private static final ThreadLocal<long[]> BYTE_TABLE = ThreadLocal.withInitial(() -> new long[256]);

    /**
     * {@link #repack} where both widths are 4 or both are 8 and the longs hold no padding. A table maps each byte of an old long to the
     * byte of the new long (two 4-bit ids, or one 8-bit id), so a long takes 8 lookups instead of an id at a time.
     */
    private static long[] repackBytes(long[] raw, int bits, int longs, int[] remap, long[] out) {
        long[] table = BYTE_TABLE.get();
        if (bits == 4) {
            for (int b = 0; b < 256; b++) {
                table[b] = (remap[b & 15] & 15) | (remap[b >>> 4] & 15) << 4;
            }
        } else {
            for (int b = 0; b < 256; b++) {
                table[b] = remap[b] & 255;
            }
        }
        for (int w = 0; w < longs; w++) {
            long in = raw[w];
            out[w] = table[(int) in & 255]
                | table[(int) (in >>> 8) & 255] << 8
                | table[(int) (in >>> 16) & 255] << 16
                | table[(int) (in >>> 24) & 255] << 24
                | table[(int) (in >>> 32) & 255] << 32
                | table[(int) (in >>> 40) & 255] << 40
                | table[(int) (in >>> 48) & 255] << 48
                | table[(int) (in >>> 56) & 255] << 56;
        }
        return out;
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
