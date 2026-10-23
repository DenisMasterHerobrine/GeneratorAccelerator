package dev.denismasterherobrine.generatoraccelerator2.memory;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.HashSet;
import java.util.Set;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;
import org.slf4j.Logger;

/**
 * Whether the heap is nearly full of live data. GA2's caches and look-ahead work then shrink, so the game keeps its memory for
 * the chunks themselves: the GPU region cache and its prefetch, the sections encoded ahead of a save, the border column cache
 * and the carver path cache.
 *
 * <p>The heap's use after each garbage collection comes from the JVM's collection notifications. After a young collection the
 * old generation still holds its dead objects, so one high value means little. The heap counts as full when the lowest value of
 * the last {@link #SAMPLES} collections is above {@link #HIGH} of the maximum heap. It counts as no longer full once that lowest
 * value is below {@link #LOW}. {@code -Dgeneratoraccelerator2.memoryPressure=0} turns this off.
 */
public final class MemoryPressure {
    private MemoryPressure() {}

    private static final Logger LOGGER = Ga2Log.get("Memory");
    private static final String GC_NOTIFICATION = "com.sun.management.gc.notification";
    static final double HIGH = Double.parseDouble(System.getProperty("generatoraccelerator2.memoryPressure", "0.85"));
    static final double LOW = HIGH - 0.15;
    static final int SAMPLES = 8;

    private static volatile boolean high;
    private static final long[] after = new long[SAMPLES];
    private static int next, count;
    private static final Set<String> HEAP_POOLS = new HashSet<>();
    private static boolean installed;

    /** True while the heap is nearly full of live data. */
    public static boolean high() {
        return high;
    }

    /** Listens to the collectors. Without collection notifications (another JVM) the heap never counts as full. */
    public static synchronized void install() {
        if (installed || HIGH <= 0) {
            return;
        }
        installed = true;
        try {
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                if (pool.getType() == MemoryType.HEAP) {
                    HEAP_POOLS.add(pool.getName());
                }
            }
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (gc instanceof NotificationEmitter emitter) {
                    emitter.addNotificationListener((n, handback) -> collected(n), null, null);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            LOGGER.info("no collection notifications ({}) -> memory pressure off", e.toString());
        }
    }

    private static void collected(Notification n) {
        if (!GC_NOTIFICATION.equals(n.getType()) || !(n.getUserData() instanceof CompositeData info)) {
            return;
        }
        long used = 0;
        try {
            CompositeData gcInfo = (CompositeData) info.get("gcInfo");
            TabularData usage = (TabularData) gcInfo.get("memoryUsageAfterGc");
            for (Object row : usage.values()) {
                CompositeData entry = (CompositeData) row;
                if (HEAP_POOLS.contains((String) entry.get("key"))) {
                    used += (Long) ((CompositeData) entry.get("value")).get("used");
                }
            }
        } catch (RuntimeException e) {
            return;
        }
        update(used, Runtime.getRuntime().maxMemory());
    }

    /** One collection left {@code used} bytes of a heap of at most {@code max}. On the collector's notification thread. */
    static synchronized void update(long used, long max) {
        after[next] = used;
        next = (next + 1) % SAMPLES;
        if (count < SAMPLES) {
            count++;
        }
        if (count < SAMPLES || max <= 0 || max == Long.MAX_VALUE) {
            return;
        }
        long low = Long.MAX_VALUE;
        for (long v : after) {
            low = Math.min(low, v);
        }
        double share = (double) low / max;
        if (!high && share > HIGH) {
            high = true;
            LOGGER.warn("heap {}% of {} MB after GC -> GA2 caches reduced", Math.round(share * 100), max >> 20);
            IdleTrims.trimAll();
        } else if (high && share < LOW) {
            high = false;
            LOGGER.info("heap {}% of {} MB after GC -> GA2 caches back to normal", Math.round(share * 100), max >> 20);
        }
    }
}
