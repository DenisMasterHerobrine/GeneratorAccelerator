package dev.denismasterherobrine.generatoraccelerator2.structure;

import com.google.common.cache.CacheBuilder;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.Takeover;
import dev.denismasterherobrine.generatoraccelerator2.compat.modernfix.ModernFixMixins;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * {@code StructureTemplateManager.get(id)}: {@code structureRepository.computeIfAbsent(id, this::tryLoad)}. A template loads from
 * its file (a read, an NBT parse, often a data upgrade), which takes tens of milliseconds for a large one. ModernFix makes the
 * repository a Guava cache with soft values. Its {@code computeIfAbsent} takes the lock of one of four segments, also for a
 * template already loaded, and holds it for the whole load. So every structure start asking for any template of that segment
 * waited for the load: 3.2 s of worker waits in one square of a 575-mod pack. Vanilla's map holds a bin's lock during a load,
 * which also stops lookups of other templates in that bin.
 *
 * <p>Here a loaded template is answered by the map's {@code get}, which takes no lock. A template not loaded loads under a lock of
 * its own id. A second thread asking for the same template waits for that load and gets its template. One asking for another
 * template does not wait. The loaded template goes in with {@code putIfAbsent}, so every caller gets the template in the map, as
 * from {@code computeIfAbsent}. Only for a map that is a {@code ConcurrentMap} (vanilla's and ModernFix's are), while no other mod
 * changes the call (hook {@code structures.templateLoads}). {@code -Dgeneratoraccelerator2.templateLoads=false} turns it off.
 */
public final class TemplateLoads {
    private TemplateLoads() {}

    public static final boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.templateLoads"));
    private static final HookTargets.Hook HOOK = HookTargets.hook(HookTargets.STRUCTURE_TEMPLATE_LOADS);

    /** Ids loading now, with the lock their loads take. An id's entry goes when the load that made it ends. */
    private static final ConcurrentHashMap<Object, Object> LOADING = new ConcurrentHashMap<>();

    /** Templates loaded here, and lookups that waited for another thread's load of the same template. */
    public static final LongAdder LOADED = new LongAdder(), WAITED = new LongAdder();

    /** {@code map.computeIfAbsent(id, load)} for the template repository. */
    @SuppressWarnings("unchecked")
    public static Object get(Map<Object, Object> map, Object id, Function<Object, Object> load, Operation<Object> original) {
        if (!ENABLED || !(map instanceof ConcurrentMap<?, ?>) || !HOOK.allowed()) {
            return original.call(map, id, load);
        }
        ConcurrentMap<Object, Object> templates = (ConcurrentMap<Object, Object>) map;
        Object kept = templates.get(id);
        if (kept != null) {
            return keepRecent(map, kept);
        }
        Object lock = new Object();
        Object held = LOADING.putIfAbsent(id, lock);
        if (held != null) {
            lock = held;
        }
        try {
            synchronized (lock) {
                kept = templates.get(id);
                if (kept != null) {
                    WAITED.increment();
                    return kept;
                }
                Object loaded = load.apply(id);
                if (loaded == null) {
                    // computeIfAbsent keeps nothing for a null value
                    return null;
                }
                LOADED.increment();
                kept = templates.putIfAbsent(id, loaded);
                return keepRecent(map, kept != null ? kept : loaded);
            }
        } finally {
            LOADING.remove(id, lock);
        }
    }

    /**
     * The soft repository GA2 made ({@link #repository}). A full collection clears soft values. Under a nearly full heap that came
     * back every few seconds, and every chunk load with structure pieces read its templates from their files again: 5% of the
     * server thread in one pack. So the last {@link #RECENT_SIZE} templates asked for are also held strongly. A template asked often
     * stays in the ring.
     */
    private static volatile Map<?, ?> softRepository;
    static final int RECENT_SIZE = 256;
    private static final Object[] RECENT = new Object[RECENT_SIZE];
    private static int recentNext;

    private static Object keepRecent(Map<Object, Object> map, Object template) {
        if (map == softRepository) {
            // racy on purpose: a lost write only lets one template go soft earlier
            int i = recentNext;
            RECENT[i] = template;
            recentNext = (i + 1) & (RECENT_SIZE - 1);
        }
        return template;
    }

    /** The server stopped. Its templates go with it. */
    public static void serverStopped() {
        softRepository = null;
        Arrays.fill(RECENT, null);
    }

    /**
     * The repository a template manager starts with, or null to keep vanilla's. When GA2 took ModernFix's dynamic structure manager over
     * (compat/Takeover), this is the cache ModernFix would have made: soft values, so the garbage collector may drop a template no
     * one holds. A dropped template loads again on its next use.
     */
    @Nullable
    public static <K, V> Map<K, V> repository() {
        if (!Takeover.cancelled().containsKey(ModernFixMixins.DYNAMIC_STRUCTURES)) {
            return null;
        }
        Map<K, V> map = CacheBuilder.newBuilder().softValues().<K, V>build().asMap();
        softRepository = map;
        return map;
    }
}
