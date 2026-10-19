package dev.denismasterherobrine.generatoraccelerator2.features.modded;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.world.level.WorldGenLevel;

/**
 * Static state of a modded feature written once from the world's seed and only read afterwards. Such a feature makes its
 * noise into static fields in a {@code setSeed(level.getSeed())} at the start of every placement. That call is guarded by
 * "the seed it was made for differs, or there is no noise yet". The feature keeps the seed in an instance field (one
 * instance per feature type). For a running world the guard holds once, at the first placement. Every later placement only
 * reads the noise. The scan counts the write as shared state, and the first placements must indeed not race with it. So
 * placements of such a feature run under the shared lock until one of them has made the noise for the world's seed. The
 * noise is then published through a volatile field. From then on, the feature's placements decorate in parallel, finding
 * the guard false and nothing to write. Reviewed per class (the noise objects are read-only once built). The scan must find
 * no other shared state in the class.
 */
public final class SeededStatics {
    private SeededStatics() {}

    /** A reviewed feature class: its instance seed field and the static fields made from it. */
    private record Entry(String type, String seedField, List<String> statics) {}

    private static final List<Entry> ENTRIES = List.of(
        // The Bumblezone 7.16: OpenSimplex2F noises (final permutation arrays filled in the constructor)
        new Entry("com.telepathicgrunt.the_bumblezone.worldgen.features.HoneycombCaves", "seed", List.of("noiseGen", "noiseGen2")),
        new Entry("com.telepathicgrunt.the_bumblezone.worldgen.features.TwoToneSpikeFeature", "seed", List.of("noiseGen")));

    private static final Map<String, Entry> BY_TYPE = new java.util.concurrent.ConcurrentHashMap<>();

    static {
        for (Entry e : ENTRIES) {
            BY_TYPE.put(e.type(), e);
        }
    }

    /** Adds a reviewed class (tests). */
    static void register(String type, String seedField, List<String> statics) {
        BY_TYPE.put(type, new Entry(type, seedField, List.copyOf(statics)));
    }

    /** The static fields of {@code type}'s entry ({@code owner.name}, internal names), or an empty set. */
    static Set<String> fields(Class<?> type) {
        Entry e = BY_TYPE.get(type.getName());
        if (e == null) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        for (String s : e.statics()) {
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
            Field seed = type.getDeclaredField(e.seedField());
            if (seed.getType() != long.class || java.lang.reflect.Modifier.isStatic(seed.getModifiers())) {
                return null;
            }
            VarHandle seedHandle = lookup.unreflectVarHandle(seed);
            VarHandle[] statics = new VarHandle[e.statics().size()];
            for (int i = 0; i < statics.length; i++) {
                Field f = type.getDeclaredField(e.statics().get(i));
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    return null;
                }
                statics[i] = lookup.unreflectVarHandle(f);
            }
            return new Init(seedHandle, statics);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e2) {
            return null; // LinkageError: a field's type is a class this side does not have
        }
    }

    /** Whether a feature instance's seeded statics are made for the level's seed and published. */
    public static final class Init {
        private final VarHandle seed;
        private final VarHandle[] statics;
        /** The seed whose noise a locked placement made and left in place. Read before every unlocked placement. */
        private volatile long publishedSeed;
        private volatile boolean published;

        Init(VarHandle seed, VarHandle[] statics) {
            this.seed = seed;
            this.statics = statics;
        }

        private boolean made(Object feature, long levelSeed) {
            if ((long) seed.get(feature) != levelSeed) {
                return false;
            }
            for (VarHandle s : statics) {
                if (s.get() == null) {
                    return false;
                }
            }
            return true;
        }

        /** True when a placement on {@code level} may run without the lock: its noise is made for this seed and published. */
        public boolean ready(Object feature, WorldGenLevel level) {
            long levelSeed = level.getSeed();
            // the volatile reads come first: what the publishing placement wrote before publishing is then visible
            return published && publishedSeed == levelSeed && made(feature, levelSeed);
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
