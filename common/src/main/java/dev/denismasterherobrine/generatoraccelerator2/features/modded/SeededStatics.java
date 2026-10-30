package dev.denismasterherobrine.generatoraccelerator2.features.modded;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.world.level.WorldGenLevel;

/**
 * State of a modded feature written once and only read afterwards. Such a feature makes its noise from the world's seed into
 * static fields, or into its own fields (one instance per feature type), in a {@code setSeed(level.getSeed())} at the start of
 * every placement. That call is guarded by "the seed it was made for differs, or there is no noise yet". The feature keeps the
 * seed in an instance field. For a running world the guard holds once, at the first placement. Every later placement only
 * reads the noise. Other features fill static maps from the registries at their first placement, guarded by "the map is still
 * empty" (an entry without a seed field). The scan counts the write as shared state, and the first placements must indeed not
 * race with it. So placements of such a feature run under the shared lock until one of them has made the state (for the
 * world's seed). The state is then published through a volatile field. From then on, the feature's placements decorate in
 * parallel, finding the guard false and nothing to write. Reviewed per class (the state is read-only once made). The scan must
 * find no other shared state in the class.
 */
public final class SeededStatics {
    private SeededStatics() {}

    /**
     * A reviewed feature class: its instance seed field and the fields made from it (static or the feature's own). Without a seed
     * field, the fields are static maps or collections, made once their first placement has filled them.
     */
    private record Entry(String type, @Nullable String seedField, List<String> made) {}

    private static final List<Entry> ENTRIES = List.of(
        // The Bumblezone 7.16: OpenSimplex2F noises (final permutation arrays filled in the constructor)
        new Entry("com.telepathicgrunt.the_bumblezone.worldgen.features.HoneycombCaves", "seed", List.of("noiseGen", "noiseGen2")),
        new Entry("com.telepathicgrunt.the_bumblezone.worldgen.features.TwoToneSpikeFeature", "seed", List.of("noiseGen")),
        // Repurposed Structures 7.1.22 and 7.1.25: an OpenSimplex2F in its own field, read only once built
        new Entry("com.telepathicgrunt.repurposedstructures.world.features.StructurePowderSnow", "seed", List.of("noiseGenerator")),
        // TerraFirmaGreg Core 0.9.23: three static HashMaps filled from the configured feature registry while the first is empty
        new Entry("su.terrafirmagreg.core.world.feature.MineshaftOreFeature", null, List.of("configMap", "sizeMap", "featureMap")));

    private static final Map<String, Entry> BY_TYPE = new ConcurrentHashMap<>();

    static {
        for (Entry e : ENTRIES) {
            BY_TYPE.put(e.type(), e);
        }
    }

    /** Adds a reviewed class (tests). */
    static void register(String type, @Nullable String seedField, List<String> made) {
        BY_TYPE.put(type, new Entry(type, seedField, List.copyOf(made)));
    }

    /** The fields of {@code type}'s entry, its seed field and the fields made from it ({@code owner.name}, internal names), or an empty set. */
    static Set<String> fields(Class<?> type) {
        Entry e = BY_TYPE.get(type.getName());
        if (e == null) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        if (e.seedField() != null) {
            out.add(type.getName().replace('.', '/') + "." + e.seedField());
        }
        for (String s : e.made()) {
            out.add(type.getName().replace('.', '/') + "." + s);
        }
        return out;
    }

    /** The ready check for {@code type}, or null when it has no entry or its fields are not as reviewed. */
    @Nullable
    static Init init(Class<?> type) {
        Entry e = BY_TYPE.get(type.getName());
        if (e == null) {
            return null;
        }
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(type, MethodHandles.lookup());
            VarHandle seedHandle = null;
            if (e.seedField() != null) {
                Field seed = type.getDeclaredField(e.seedField());
                if (seed.getType() != long.class || Modifier.isStatic(seed.getModifiers())) {
                    return null;
                }
                seedHandle = lookup.unreflectVarHandle(seed);
            }
            VarHandle[] made = new VarHandle[e.made().size()];
            boolean[] own = new boolean[made.length];
            for (int i = 0; i < made.length; i++) {
                Field f = type.getDeclaredField(e.made().get(i));
                if (f.getType().isPrimitive()) {
                    return null; // "not made yet" is a null reference
                }
                if (seedHandle == null && (!Modifier.isStatic(f.getModifiers())
                    || !Map.class.isAssignableFrom(f.getType()) && !Collection.class.isAssignableFrom(f.getType()))) {
                    return null; // without a seed, "made" means a filled static map or collection
                }
                own[i] = !Modifier.isStatic(f.getModifiers());
                made[i] = lookup.unreflectVarHandle(f);
            }
            return new Init(seedHandle, made, own);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e2) {
            return null; // LinkageError: a field's type is a class this side does not have
        }
    }

    /** Whether a feature instance's state is made (for the level's seed) and published. */
    public static final class Init {
        /** The feature's seed field, or null for state made without a seed. */
        @Nullable
        private final VarHandle seed;
        private final VarHandle[] made;
        /** Per field of {@link #made}: true for the feature's own field, false for a static one. */
        private final boolean[] own;
        /** The seed whose noise a locked placement made and left in place. Read before every unlocked placement. */
        private volatile long publishedSeed;
        private volatile boolean published;

        Init(@Nullable VarHandle seed, VarHandle[] made, boolean[] own) {
            this.seed = seed;
            this.made = made;
            this.own = own;
        }

        private boolean made(Object feature, long levelSeed) {
            if (seed != null && (long) seed.get(feature) != levelSeed) {
                return false;
            }
            for (int i = 0; i < made.length; i++) {
                Object value = own[i] ? made[i].get(feature) : made[i].get();
                if (value == null || seed == null && (value instanceof Map<?, ?> m ? m.isEmpty() : ((Collection<?>) value).isEmpty())) {
                    return false;
                }
            }
            return true;
        }

        /** True when a placement on {@code level} may run without the lock: its noise is made for this seed and published. */
        public boolean ready(Object feature, WorldGenLevel level) {
            long levelSeed = level.getSeed();
            // the volatile reads come first: what the publishing placement wrote before publishing is then visible
            return published && (seed == null || publishedSeed == levelSeed) && made(feature, levelSeed);
        }

        /** After a placement under the lock: publishes the noise when the feature made it for this seed. */
        public void publish(Object feature, WorldGenLevel level) {
            long levelSeed = level.getSeed();
            if (made(feature, levelSeed)) {
                publishedSeed = levelSeed;
                published = true;
            }
        }
    }
}
