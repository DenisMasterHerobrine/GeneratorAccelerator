package dev.denismasterherobrine.generatoraccelerator2.features.modded;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.features.FeaturesConfig;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;
import org.slf4j.Logger;

/**
 * What GA2 knows about feature classes that are not vanilla's, decided the first time each is placed.
 *
 * <ul>
 * <li><b>Shared state</b> ({@link FeatureSafetyScan}): a feature whose code writes static fields or changes static
 * collections places one at a time, as all decorations did on vanilla's single worldgen thread. All such features share
 * one lock. Every other feature keeps decorating in parallel.</li>
 * <li><b>Fast paths</b> ({@link #fastPath}): a mod's subclass of a vanilla feature that GA2 has a fast path for uses that
 * fast path when the subclass changes none of the methods the fast path does the work of. Such a subclass only adds a
 * constructor, a codec, or fields the vanilla code never reads.</li>
 * </ul>
 * Every placement still runs the feature's own code or an exact transcription of it. Nothing here changes what is placed.
 */
public final class ModdedFeatures {
    private ModdedFeatures() {}

    private static final Logger LOGGER = Ga2Log.get("Features");

    /**
     * A feature class: its mod (module), why its placements would share state (null if not), and the lock they take then. With
     * {@code copies}, the state is the feature's own per-placement fields, and each thread places on its own copy instead
     * ({@link FeatureCopies}).
     */
    public record Profile(String type, String module, @Nullable String sharedState, @Nullable Object lock, @Nullable SeededStatics.Init init,
                          boolean copies, LongAdder placements) {}

    /**
     * One lock for every feature that shares state. Such state may be shared between a mod's feature classes. With one
     * lock, a feature that places another feature (of another mod) can never wait for a lock held by a placement that is
     * waiting for it.
     */
    private static final Object SHARED_LOCK = new Object();
    private static final Map<String, LongAdder> PLACEMENTS = new ConcurrentHashMap<>();
    private static final Map<String, Profile> SEEN = new ConcurrentHashMap<>();

    private static final ClassValue<Profile> PROFILES = new ClassValue<>() {
        @Override
        protected Profile computeValue(Class<?> type) {
            // vanilla's own classes have none. The check was a string comparison per placement
            return type.getName().startsWith("net.minecraft.") ? null : profile(type);
        }
    };

    /** The profile of a feature class, or null for vanilla's own classes. */
    @Nullable
    public static Profile of(Class<?> type) {
        return PROFILES.get(type);
    }

    private static Profile profile(Class<?> type) {
        String module = type.getModule().isNamed() ? type.getModule().getName() : type.getPackageName();
        String shared = null;
        if (FeaturesConfig.scanModdedFeatures()) {
            try {
                shared = FeatureSafetyScan.sharedState(type);
            } catch (Throwable t) {
                shared = "its code could not be checked (" + t + ")";
            }
        }
        // state written once from the world's seed (reviewed, SeededStatics). One at a time only until it is made.
        SeededStatics.Init init = null;
        if (shared != null && !SeededStatics.fields(type).isEmpty()) {
            try {
                if (FeatureSafetyScan.sharedState(type, false, FeatureSafetyScan.MAX_METHODS, false, SeededStatics.fields(type)) == null) {
                    init = SeededStatics.init(type);
                }
            } catch (Throwable t) {
                init = null;
            }
        }
        // state only in its own fields, assigned anew in every placement: a copy per thread
        boolean copies = false;
        if (shared != null && init == null && FeaturesConfig.scanModdedFeatures()) {
            try {
                copies = FeatureSafetyScan.perPlacementState(type);
            } catch (Throwable t) {
                copies = false;
            }
        }
        Object lock = shared == null ? null : SHARED_LOCK;
        Profile p = new Profile(type.getName(), module, shared, lock, init, copies, PLACEMENTS.computeIfAbsent(type.getName(), k -> new LongAdder()));
        SEEN.put(type.getName(), p);
        if (shared != null && init != null) {
            LOGGER.info("feature {} ({}): {} (seeded) -> serialized until initialized",
                type.getName(), module, shared);
        } else if (copies) {
            LOGGER.info("feature {} ({}): {}, assigned anew in every placement -> parallel on per-thread copies", type.getName(), module, shared);
        } else if (shared != null) {
            LOGGER.info("feature {} ({}): {} -> serialized", type.getName(), module, shared);
        } else {
            LOGGER.debug("feature {} ({}): no shared state -> parallel", type.getName(), module);
        }
        return p;
    }

    /** Counts a placement of a modded feature (for the report). */
    public static void placed(Profile profile) {
        profile.placements().increment();
    }

    // ------------------------------------------------------------------------------------------------ fast path eligibility

    private static final Map<Class<?>, ClassValue<Boolean>> ELIGIBLE = new ConcurrentHashMap<>();

    /**
     * Whether {@code type}, a vanilla feature class with a fast path or a subclass of it, may use that fast path. True for
     * the class itself. True for a subclass that declares no instance method that the vanilla class or its superclasses (up
     * to {@code Object}) also declare. Then every method the fast path stands in for is still vanilla's.
     */
    public static boolean fastPath(Class<?> type, Class<?> vanilla) {
        if (type == vanilla) {
            return true;
        }
        if (!FeaturesConfig.moddedFastPaths()) {
            return false;
        }
        return ELIGIBLE.computeIfAbsent(vanilla, v -> new ClassValue<>() {
            @Override
            protected Boolean computeValue(Class<?> t) {
                boolean ok = keepsVanillaMethods(t, v);
                if (ok) {
                    LOGGER.info("feature {}: all {} methods vanilla -> fast path", t.getName(), v.getSimpleName());
                }
                return ok;
            }
        }).get(type);
    }

    private static boolean keepsVanillaMethods(Class<?> type, Class<?> vanilla) {
        if (!vanilla.isAssignableFrom(type)) {
            return false;
        }
        try {
            for (Class<?> c = type; c != vanilla; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    int mod = m.getModifiers();
                    if (Modifier.isStatic(mod) || Modifier.isPrivate(mod) || m.isSynthetic()) {
                        continue;
                    }
                    if (declaredAbove(m, vanilla)) {
                        return false;
                    }
                }
            }
        } catch (LinkageError e) {
            // a method signature names a class this side does not have (a client class on a dedicated server). The feature's own code runs.
            return false;
        }
        return true;
    }

    /** Whether the vanilla class or one of its superclasses below {@code Object} declares an instance method like {@code m}. */
    private static boolean declaredAbove(Method m, Class<?> vanilla) {
        for (Class<?> c = vanilla; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method v = c.getDeclaredMethod(m.getName(), m.getParameterTypes());
                if (!Modifier.isStatic(v.getModifiers()) && !Modifier.isPrivate(v.getModifiers())) {
                    return true;
                }
            } catch (NoSuchMethodException ignored) {
                // not declared here
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------ report

    /** Lines for {@code /ga2 features mods}. */
    public static List<String> report() {
        List<String> out = new ArrayList<>();
        List<Profile> profiles = new ArrayList<>(SEEN.values());
        profiles.sort(Comparator.comparing(Profile::module).thenComparing(Profile::type));
        long shared = profiles.stream().filter(p -> p.sharedState() != null && !p.copies()).count();
        long copied = profiles.stream().filter(Profile::copies).count();
        out.add("modded features placed: " + profiles.size() + " classes, " + shared + " of them one placement at a time, " + copied
            + " on per-thread copies (" + FeatureCopies.MADE.sum() + " copies)"
            + (FeaturesConfig.scanModdedFeatures() ? "" : " (scan off in config)"));
        for (Profile p : profiles) {
            LongAdder n = PLACEMENTS.get(p.type());
            out.add("  " + p.module() + " " + p.type().substring(p.type().lastIndexOf('.') + 1) + ": " + (n == null ? 0 : n.sum()) + " placements, "
                + (p.sharedState() == null ? "parallel" : p.copies() ? "parallel on per-thread copies: " + p.sharedState() : "one at a time: " + p.sharedState()));
        }
        return out;
    }
}
