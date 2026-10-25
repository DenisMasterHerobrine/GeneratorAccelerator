package dev.denismasterherobrine.generatoraccelerator2.light;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectFunction;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiFunction;
import java.util.function.LongFunction;
import javax.annotation.Nullable;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.DataLayer;
import org.slf4j.Logger;

/**
 * The published light data of one light layer, built from the sections a batch changed.
 *
 * <p>Other threads read light from a published copy of the section map (vanilla's {@code visibleSectionData}). At the end
 * of every batch that changed a section, vanilla's {@code swapSectionMap} publishes a new copy. That copy is a clone of the
 * whole map, so it holds every section of the dimension that stores light. A clone costs 0.1-0.8 ms per light layer at
 * 26,000-151,000 sections, however little the batch changed ({@code LightSnapshotBench}). A snapshot holds the same
 * sections in areas of 8x8 chunk columns. Each area is one array of section data, by column and then by height over the
 * level's light sections. A new snapshot shares every area of the previous one that no changed section falls into. It
 * copies the other areas and writes the changed sections in. That costs 7-20 µs per light layer in the same bench. A
 * lookup is a small map lookup and an array index. It takes as long as in the clone for scattered sections, and about
 * half as long for sections read column by column.
 *
 * <p>Vanilla records every section it replaces, adds or removes in the working map in {@code changedSections}. The
 * publish clears that set. GA2's parallel and GPU propagation record their copied sections there too. A snapshot
 * therefore holds exactly what a clone of the working map would hold, with the same section data objects. Vanilla
 * publishes only when the set is not empty, and so does this.
 *
 * <p>A snapshot never changes once published. Readers on any thread look a section up in its area. Any other
 * use of the map (iteration, a change) first fills the map's own table with every section, once. From then on the map
 * works as a plain map.
 *
 * <p>{@code -Dgeneratoraccelerator2.lightSnapshots=false} turns it off.
 */
public final class LightSnapshot extends Long2ObjectOpenHashMap<DataLayer> {
    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = Ga2Log.get("Light");

    /** Not final so tests can compare both ways. Call {@link #refresh()} after a change. */
    public static boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.lightSnapshots"));

    /** Read at every publish. Set by {@link #refresh()}. */
    public static boolean on;
    /** Set when building a snapshot failed. Every later publish is vanilla's clone. */
    private static volatile boolean failed;

    /** Snapshots built from the previous one and the changed sections. */
    public static final LongAdder DELTAS = new LongAdder();
    /** Snapshots built from a whole working map (the first publish of a storage, or the first after vanilla's clone). */
    public static final LongAdder FULL = new LongAdder();
    public static final LongAdder CHANGED = new LongAdder();
    public static final LongAdder NANOS = new LongAdder();

    /** An area is 2^AREA_SHIFT chunk columns on a side. */
    private static final int AREA_SHIFT = 3;
    private static final int AREA_MASK = (1 << AREA_SHIFT) - 1;
    /** Chunk columns per area. */
    private static final int COLUMNS = 1 << 2 * AREA_SHIFT;

    public static void refresh() {
        on = ENABLED && !failed && LightConfig.copyChangedOnly() && HookTargets.allowed(HookTargets.LIGHT_PUBLISH);
    }

    /** Stops building snapshots after an error. Publishing then falls back to vanilla's clone. */
    public static void fail(RuntimeException e) {
        if (!failed) {
            failed = true;
            on = false;
            LOGGER.error("light snapshot failed -> whole section maps copied from now on", e);
        }
    }

    /**
     * One build of a snapshot. Areas the previous snapshot holds are shared until a section of theirs changes. They are copied
     * then, once per build. The section counts per area are only read by builds, so one map of them goes from build to build.
     */
    private static final class Build {
        final Long2ObjectOpenHashMap<DataLayer[]> areas;
        final Long2IntOpenHashMap counts;
        /** Areas this build copied or made. It writes them in place. */
        final LongOpenHashSet own = new LongOpenHashSet();
        int minY;
        int height;
        int count;

        Build(Long2ObjectOpenHashMap<DataLayer[]> areas, Long2IntOpenHashMap counts, int minY, int height, int count) {
            this.areas = areas;
            this.counts = counts;
            this.minY = minY;
            this.height = height;
            this.count = count;
        }

        /** Writes one section. A null layer removes it. */
        void write(long key, @Nullable DataLayer layer) {
            int x = SectionPos.x(key);
            int y = SectionPos.y(key);
            int z = SectionPos.z(key);
            long areaKey = areaKey(x, z);
            DataLayer[] layers = this.areas.get(areaKey);
            int i = y - this.minY;
            boolean inside = i >= 0 && i < this.height;
            DataLayer old = layers != null && inside ? layers[slot(x, z) * this.height + i] : null;
            if (old == layer) {
                return;
            }
            if (layer == null) {
                // The old layer is not null here, so the area holds the section.
                this.own(areaKey, layers)[slot(x, z) * this.height + i] = null;
                this.count--;
                if (this.counts.addTo(areaKey, -1) == 1) {
                    this.areas.remove(areaKey);
                    this.counts.remove(areaKey);
                    this.own.remove(areaKey);
                }
                return;
            }
            if (!inside) {
                this.widen(y);
                layers = this.areas.get(areaKey);
                i = y - this.minY;
            }
            if (layers == null) {
                layers = new DataLayer[COLUMNS * this.height];
                this.areas.put(areaKey, layers);
                this.own.add(areaKey);
            } else {
                layers = this.own(areaKey, layers);
            }
            layers[slot(x, z) * this.height + i] = layer;
            if (old == null) {
                this.count++;
                this.counts.addTo(areaKey, 1);
            }
        }

        /** The area's layers to write: a copy, unless this build already copied or made them. */
        private DataLayer[] own(long areaKey, DataLayer[] layers) {
            if (this.own.add(areaKey)) {
                layers = layers.clone();
                this.areas.put(areaKey, layers);
            }
            return layers;
        }

        /** Lays every area out again with heights that also hold section y. Only a section outside the level's light sections does this. */
        private void widen(int y) {
            int lo = Math.min(this.minY, y);
            int height = Math.max(this.minY + this.height - 1, y) - lo + 1;
            for (Long2ObjectMap.Entry<DataLayer[]> e : Long2ObjectMaps.fastIterable(this.areas)) {
                DataLayer[] old = e.getValue();
                DataLayer[] layers = new DataLayer[COLUMNS * height];
                for (int slot = 0; slot < COLUMNS; slot++) {
                    System.arraycopy(old, slot * this.height, layers, slot * height + this.minY - lo, this.height);
                }
                e.setValue(layers);
                this.own.add(e.getLongKey());
            }
            this.minY = lo;
            this.height = height;
        }
    }

    /** The sections of each area's 8x8 chunk columns, by column slot and then section y from {@link #minY}. Never changed once published. */
    private final transient Long2ObjectOpenHashMap<DataLayer[]> areas;
    private final int minY;
    /** Section heights per column. */
    private final int height;
    private final int count;
    /** Sections per area for the next build. Every build changes it, so only the last snapshot of a storage can be built on. */
    private final transient Long2IntOpenHashMap counts;
    /** Whether the map's own table holds every section ({@link #fill}). */
    private volatile boolean filled;

    private LightSnapshot(Build build) {
        super(0);
        this.areas = build.areas;
        this.minY = build.minY;
        this.height = build.height;
        this.count = build.count;
        this.counts = build.counts;
    }

    /**
     * The snapshot to publish for a working map. Built from {@code previous}, the last snapshot built for the storage, and the
     * sections in {@code changed}. Without a previous snapshot, built from the whole working map. Each column has the
     * heights from {@code minY} for {@code height} sections, the level's light sections, widened when a section lies outside.
     * {@code previous} stays as it was.
     */
    public static LightSnapshot next(@Nullable LightSnapshot previous, Long2ObjectOpenHashMap<DataLayer> working, LongSet changed, int minY, int height) {
        long t0 = System.nanoTime();
        Build build;
        if (previous == null) {
            int lo = minY;
            int hi = minY + height - 1;
            for (LongIterator it = working.keySet().iterator(); it.hasNext(); ) {
                int y = SectionPos.y(it.nextLong());
                lo = Math.min(lo, y);
                hi = Math.max(hi, y);
            }
            build = new Build(new Long2ObjectOpenHashMap<>(), new Long2IntOpenHashMap(), lo, hi - lo + 1, 0);
            for (Long2ObjectMap.Entry<DataLayer> e : Long2ObjectMaps.fastIterable(working)) {
                build.write(e.getLongKey(), e.getValue());
            }
            FULL.increment();
        } else {
            build = new Build(previous.areas.clone(), previous.counts, previous.minY, previous.height, previous.count);
            for (LongIterator it = changed.iterator(); it.hasNext(); ) {
                long key = it.nextLong();
                build.write(key, working.get(key));
            }
            DELTAS.increment();
            CHANGED.add(changed.size());
        }
        LightSnapshot next = new LightSnapshot(build);
        NANOS.add(System.nanoTime() - t0);
        return next;
    }

    private static long areaKey(int x, int z) {
        return (long) (x >> AREA_SHIFT) << 32 | (z >> AREA_SHIFT) & 0xFFFFFFFFL;
    }

    private static int slot(int x, int z) {
        return x & AREA_MASK | (z & AREA_MASK) << AREA_SHIFT;
    }

    @Nullable
    private DataLayer find(long key) {
        int x = SectionPos.x(key);
        int z = SectionPos.z(key);
        DataLayer[] layers = this.areas.get(areaKey(x, z));
        int i = SectionPos.y(key) - this.minY;
        return layers != null && i >= 0 && i < this.height ? layers[slot(x, z) * this.height + i] : null;
    }

    /** Fills the map's own table with every section, once. Every use but a lookup goes to that table from then on. */
    private void fill() {
        if (this.filled) {
            return;
        }
        synchronized (this) {
            if (this.filled) {
                return;
            }
            super.ensureCapacity(this.count);
            for (Long2ObjectMap.Entry<DataLayer[]> e : Long2ObjectMaps.fastIterable(this.areas)) {
                int x0 = (int) (e.getLongKey() >> 32) << AREA_SHIFT;
                int z0 = (int) e.getLongKey() << AREA_SHIFT;
                DataLayer[] layers = e.getValue();
                for (int i = 0; i < layers.length; i++) {
                    if (layers[i] != null) {
                        int slot = i / this.height;
                        super.put(SectionPos.asLong(x0 | slot & AREA_MASK, this.minY + i % this.height, z0 | slot >> AREA_SHIFT), layers[i]);
                    }
                }
            }
            this.filled = true;
        }
    }

    /** Status lines for {@code /ga2 light}. */
    public static List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        String state = !ENABLED ? "off (-Dgeneratoraccelerator2.lightSnapshots=false)" : failed ? "off (failed, see the log)"
            : !LightConfig.copyChangedOnly() ? "off (light.toml copyChangedOnly)" : !HookTargets.allowed(HookTargets.LIGHT_PUBLISH)
            ? "off (another mod changes how light is published)" : "on";
        lines.add("light snapshots: " + state);
        long deltas = DELTAS.sum();
        long full = FULL.sum();
        lines.add(String.format(java.util.Locale.ROOT, "  %,d published from the changed sections (%.1f sections each), %,d from whole maps, %.3f ms each",
            deltas, deltas == 0 ? 0.0 : (double) CHANGED.sum() / deltas, full, deltas + full == 0 ? 0.0 : NANOS.sum() / 1e6 / (deltas + full)));
        return lines;
    }

    // Lookups answer from the areas until the map is filled.

    @Override
    public DataLayer get(long key) {
        return this.filled ? super.get(key) : this.find(key);
    }

    @Override
    public boolean containsKey(long key) {
        return this.filled ? super.containsKey(key) : this.find(key) != null;
    }

    @Override
    public DataLayer getOrDefault(long key, DataLayer defaultValue) {
        if (this.filled) {
            return super.getOrDefault(key, defaultValue);
        }
        DataLayer layer = this.find(key);
        return layer != null ? layer : defaultValue;
    }

    @Override
    public int size() {
        return this.filled ? super.size() : this.count;
    }

    @Override
    public boolean isEmpty() {
        return this.size() == 0;
    }

    // Every other use fills the table first.

    @Override
    public void ensureCapacity(int capacity) {
        this.fill();
        super.ensureCapacity(capacity);
    }

    @Override
    public void putAll(Map<? extends Long, ? extends DataLayer> m) {
        this.fill();
        super.putAll(m);
    }

    @Override
    public DataLayer put(long key, DataLayer value) {
        this.fill();
        return super.put(key, value);
    }

    @Override
    public DataLayer remove(long key) {
        this.fill();
        return super.remove(key);
    }

    @Override
    public boolean containsValue(Object value) {
        this.fill();
        return super.containsValue(value);
    }

    @Override
    public DataLayer putIfAbsent(long key, DataLayer value) {
        this.fill();
        return super.putIfAbsent(key, value);
    }

    @Override
    public boolean remove(long key, Object value) {
        this.fill();
        return super.remove(key, value);
    }

    @Override
    public boolean replace(long key, DataLayer oldValue, DataLayer value) {
        this.fill();
        return super.replace(key, oldValue, value);
    }

    @Override
    public DataLayer replace(long key, DataLayer value) {
        this.fill();
        return super.replace(key, value);
    }

    @Override
    public DataLayer computeIfAbsent(long key, LongFunction<? extends DataLayer> mapping) {
        this.fill();
        return super.computeIfAbsent(key, mapping);
    }

    @Override
    public DataLayer computeIfAbsent(long key, Long2ObjectFunction<? extends DataLayer> mapping) {
        this.fill();
        return super.computeIfAbsent(key, mapping);
    }

    @Override
    public DataLayer computeIfPresent(long key, BiFunction<? super Long, ? super DataLayer, ? extends DataLayer> remapping) {
        this.fill();
        return super.computeIfPresent(key, remapping);
    }

    @Override
    public DataLayer compute(long key, BiFunction<? super Long, ? super DataLayer, ? extends DataLayer> remapping) {
        this.fill();
        return super.compute(key, remapping);
    }

    @Override
    public DataLayer merge(long key, DataLayer value, BiFunction<? super DataLayer, ? super DataLayer, ? extends DataLayer> remapping) {
        this.fill();
        return super.merge(key, value, remapping);
    }

    @Override
    public void clear() {
        this.fill();
        super.clear();
    }

    @Override
    public Long2ObjectMap.FastEntrySet<DataLayer> long2ObjectEntrySet() {
        this.fill();
        return super.long2ObjectEntrySet();
    }

    @Override
    public LongSet keySet() {
        this.fill();
        return super.keySet();
    }

    @Override
    public ObjectCollection<DataLayer> values() {
        this.fill();
        return super.values();
    }

    @Override
    public boolean trim() {
        this.fill();
        return super.trim();
    }

    @Override
    public boolean trim(int n) {
        this.fill();
        return super.trim(n);
    }

    @Override
    public Long2ObjectOpenHashMap<DataLayer> clone() {
        this.fill();
        return super.clone();
    }

    @Override
    public int hashCode() {
        this.fill();
        return super.hashCode();
    }
}
