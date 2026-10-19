package dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.features.region.TagEpoch;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;

/**
 * Lithostitched's partial biome replacements ({@code ReplacePartially}). A large pack has a hundred or more. For every
 * noise biome, Lithostitched's injector source walks all of them in order and takes the first that matches. Each
 * replacement first asks its target set whether the set holds the biome. If not, the replacement answers no right away.
 * That is the case for nearly all replacements. Per biome, GA2 hands the walk only the replacements whose targets hold
 * the biome, in the same order. The first match is unchanged. The left-out replacements would have answered no without
 * doing anything else.
 *
 * <p>Target sets that are written-out lists never change. Tag sets are read again whenever tags are bound
 * ({@link TagEpoch}). A list with any other kind of set, or with entries of another shape, is walked whole.
 * Lithostitched's classes are reached by name, with no dependency on the mod.
 */
public final class LithostitchedInjectors {
    private LithostitchedInjectors() {}

    static final String REPLACE_PARTIALLY = "dev.worldgen.lithostitched.impl.worldgen.biomeinjector.ReplacePartially";

    /** {@code -Dgeneratoraccelerator2.lithostitched.injectorIndex=false} walks every partial replacement. */
    private static final boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.lithostitched.injectorIndex"));

    /** Walks handed a subset, and the replacements left out. Counted per thread, since a shared counter costs more per biome lookup. */
    public static long filtered() {
        long n = 0;
        for (Noted t : ALL) {
            n += t.filtered;
        }
        return n;
    }

    public static long skipped() {
        long n = 0;
        for (Noted t : ALL) {
            n += t.skipped;
        }
        return n;
    }

    private record Shape(Object codec, Class<?> type, MethodHandle targets) {}

    private static volatile Object shape;
    private static final Object NO_SHAPE = new Object();

    /** One injector list's subsets per biome, for one binding of tags. */
    private static final class Index {
        final List<?> injectors;
        final HolderSet<?>[] targets;
        final int epoch;
        volatile Map<Holder<?>, List<Object>> byBiome = new IdentityHashMap<>();

        Index(List<?> injectors, HolderSet<?>[] targets, int epoch) {
            this.injectors = injectors;
            this.targets = targets;
            this.epoch = epoch;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        List<Object> forBiome(Holder<?> biome) {
            List<Object> subset = byBiome.get(biome);
            if (subset != null) {
                return subset;
            }
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < targets.length; i++) {
                if (((HolderSet) targets[i]).contains(biome)) {
                    out.add(injectors.get(i));
                }
            }
            subset = List.copyOf(out);
            synchronized (this) {
                Map<Holder<?>, List<Object>> next = new IdentityHashMap<>(byBiome);
                next.put(biome, subset);
                byBiome = next;
            }
            return subset;
        }
    }

    /** Injector list (by identity) to its index. A snapshot map, replaced under a lock when an index is added. */
    private static volatile Map<List<?>, Index> indexes = new IdentityHashMap<>();

    /** Per thread: the counts, and the {@code biome} local of the injector source's current lookup, noted as it is assigned. */
    private static final class Noted {
        Holder<?> biome;
        long filtered, skipped;
    }

    private static final java.util.Queue<Noted> ALL = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /**
     * Called when a server stops. The indexes of the server's injector lists lead to the server's registries, and so do the biomes
     * noted last on each thread, because worker threads outlive the server. Both are dropped. The next world's lookups build their own.
     */
    public static void forgetWorlds() {
        synchronized (LithostitchedInjectors.class) {
            indexes = new IdentityHashMap<>();
        }
        for (Noted n : ALL) {
            n.biome = null;
        }
    }
    private static final ThreadLocal<Noted> CURRENT = ThreadLocal.withInitial(() -> {
        Noted n = new Noted();
        ALL.add(n);
        return n;
    });

    /** {@code -Dgeneratoraccelerator2.verify.injectors=true}: every partial replacement asked checks that it is asked about the noted biome. */
    public static final boolean VERIFY = Boolean.getBoolean("generatoraccelerator2.verify.injectors");
    public static final LongAdder CHECKED = new LongAdder();
    public static final LongAdder MISNOTED = new LongAdder();

    public static void checkNoted(Holder<?> biome) {
        CHECKED.increment();
        Holder<?> noted = CURRENT.get().biome;
        if (noted != biome) {
            MISNOTED.increment();
            if (MISNOTED.sum() <= 10) {
                Ga2Log.get("Verify").warn("Lithostitched injectors mismatch: queried {}, noted {}",
                    biome == null ? null : biome.getRegisteredName(), noted == null ? null : noted.getRegisteredName());
            }
        }
    }

    public static Holder<?> currentBiome() {
        return CURRENT.get().biome;
    }

    public static void noteBiome(Holder<?> biome) {
        CURRENT.get().biome = biome;
    }

    /**
     * The injectors that the walk for {@code type} needs to see for the biome noted on this thread. For partial replacements, these are
     * the ones whose targets hold the biome. Otherwise this is {@code list} as it is.
     */
    public static Object candidates(Object type, Object list) {
        if (!ENABLED || !(list instanceof List<?> injectors) || injectors.size() < 4) {
            return list;
        }
        Noted noted = CURRENT.get();
        Holder<?> biome = noted.biome;
        if (biome == null) {
            return list;
        }
        Shape s = shape();
        if (s == null || type != s.codec) {
            return list;
        }
        Index index = index(injectors, s);
        if (index == null) {
            return list;
        }
        List<Object> subset = index.forBiome(biome);
        noted.filtered++;
        noted.skipped += injectors.size() - subset.size();
        return subset;
    }

    private static Index index(List<?> injectors, Shape s) {
        int epoch = TagEpoch.current();
        Index index = indexes.get(injectors);
        if (index != null && index.epoch == epoch) {
            return index;
        }
        if (index != null && index.targets == null) {
            return null;
        }
        HolderSet<?>[] targets = new HolderSet<?>[injectors.size()];
        try {
            for (int i = 0; i < targets.length; i++) {
                Object injector = injectors.get(i);
                if (!s.type.isInstance(injector)) {
                    targets = null;
                    break;
                }
                Object set = s.targets.invoke(injector);
                if (!(set instanceof HolderSet.Direct<?> || set instanceof HolderSet.Named<?>)) {
                    Ga2Log.get("Compat").info("Lithostitched partial replacement targets {} -> full walk of {} injectors",
                        set == null ? "null" : set.getClass().getName(), injectors.size());
                    targets = null;
                    break;
                }
                targets[i] = (HolderSet<?>) set;
            }
        } catch (Throwable t) {
            targets = null;
        }
        Index built = new Index(injectors, targets, epoch);
        synchronized (LithostitchedInjectors.class) {
            Map<List<?>, Index> next = new IdentityHashMap<>(indexes);
            next.put(injectors, built);
            indexes = next;
        }
        return targets == null ? null : built;
    }

    private static Shape shape() {
        Object s = shape;
        if (s == null) {
            synchronized (LithostitchedInjectors.class) {
                s = shape;
                if (s == null) {
                    s = resolve();
                    shape = s;
                }
            }
        }
        return s instanceof Shape found ? found : null;
    }

    private static Object resolve() {
        try {
            Class<?> type = Class.forName(REPLACE_PARTIALLY, false, LithostitchedInjectors.class.getClassLoader());
            Field codec = type.getField("CODEC");
            MethodHandle targets = MethodHandles.publicLookup().findVirtual(type, "targets", MethodType.methodType(HolderSet.class))
                .asType(MethodType.methodType(Object.class, Object.class));
            return new Shape(codec.get(null), type, targets);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Ga2Log.get("Compat").info("Lithostitched partial replacements: unknown shape ({}) -> walked as-is", e.toString());
            return NO_SHAPE;
        }
    }
}
