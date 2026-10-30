package dev.denismasterherobrine.generatoraccelerator2.compat.chunky;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.memory.MemoryPressure;
import dev.denismasterherobrine.generatoraccelerator2.compat.Reflect;
import dev.denismasterherobrine.generatoraccelerator2.scheduling.SchedulingConfig;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.TicketType;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.world.level.dimension.LevelStem;
import org.slf4j.Logger;

/**
 * How many chunks Chunky requests at once. Chunky keeps at most {@code chunky.maxWorkingCount} requests open (50 by default). It
 * reads the system property once, when its first task starts. With GA2 a chunk takes about 100 ms from request to full chunk,
 * mostly waiting between threads, so 50 open requests hold a pregeneration far below what the CPU can do. GA2 sets the property
 * at mod construction, before any task, for a world of vanilla height. At server start, still before any task, it sets it again
 * for the overworld's height: a taller world's chunks hold more sections, so fewer requests fit in the heap. A
 * {@code -Dchunky.maxWorkingCount} JVM argument is kept. Nothing is saved differently, and without GA2 Chunky is back at its own
 * default.
 *
 * <p>While the heap is nearly full ({@link MemoryPressure}), Chunky's task thread waits before each new request until fewer than
 * Chunky's own 50 are open ({@code mixin.GenerationTaskMixin}). A large pack's heavy chunks on top of GA2's count once filled a 12 GB
 * heap, and the collector then took the rate from 250 to 20 chunks a second. Fewer open requests let the loaded chunks go.
 */
public final class ChunkyRequests {
    private ChunkyRequests() {}

    public static final String MOD_ID = "chunky";
    static final String PROPERTY = "chunky.maxWorkingCount";
    /** Chunky's world class per loader. Its static ticket type fields hold the types of its tickets. */
    private static final String[] WORLD_CLASSES = { "org.popcraft.chunky.platform.NeoForgeWorld", "org.popcraft.chunky.platform.FabricWorld" };
    /** Chunky's own default. Automatic counts never go below it. */
    static final int CHUNKY_DEFAULT = 50;
    /** On 16 threads 256 open requests were as fast as 512 (vanilla terrain, GPU on). */
    static final int PER_PROCESSOR = 16;
    static final int MAX = 1024;
    /**
     * Heap allowed per open request in a world of {@link #BASE_SECTIONS} sections. More open requests keep more chunks loaded:
     * halfway through a pregeneration of vanilla terrain the live heap was 0.45 GB with 50, 1.2 GB with 256 and 3.6 GB with 512.
     */
    static final long HEAP_PER_REQUEST = 24L << 20;
    /** The vanilla overworld's height in sections (384 blocks), where {@link #HEAP_PER_REQUEST} was measured. */
    static final int BASE_SECTIONS = 24;

    private static final Logger LOGGER = Ga2Log.get("Compat");

    /** GA2 set the property, not a JVM argument. A server start may then set it again for the world's height. */
    private static volatile boolean ours;

    public static void register() {
        String set = System.getProperty(PROPERTY);
        if (set != null) {
            LOGGER.info("Chunky requests in flight: {} from the JVM arguments -> kept", set);
            return;
        }
        ours = true;
        int count = count(BASE_SECTIONS);
        System.setProperty(PROPERTY, Integer.toString(count));
        LOGGER.info("Chunky requests in flight: {} (Chunky's default {})", count, CHUNKY_DEFAULT);
    }

    /**
     * The count again for the overworld's height, at server start. Chunky has started no task yet then. Chunky reads the property
     * once per game, so a later world of another height keeps the first world's count.
     */
    public static void serverStarting(MinecraftServer server) {
        if (!ours) {
            return;
        }
        int sections = overworldSections(server);
        int count = count(sections);
        String value = Integer.toString(count);
        if (!value.equals(System.getProperty(PROPERTY))) {
            System.setProperty(PROPERTY, value);
            LOGGER.info("Chunky requests in flight: {} for a world {} blocks high", count, sections * 16);
        }
    }

    private static int count(int sections) {
        Runtime rt = Runtime.getRuntime();
        return inFlight(SchedulingConfig.chunkyInFlight(), rt.availableProcessors(), rt.maxMemory(), sections);
    }

    /** The overworld's height in sections, from its dimension type. {@link #BASE_SECTIONS} when the world has no overworld. */
    static int overworldSections(MinecraftServer server) {
        try {
            LevelStem overworld = server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM).getValue(LevelStem.OVERWORLD);
            return overworld != null ? overworld.type().value().height() >> 4 : BASE_SECTIONS;
        } catch (RuntimeException e) {
            return BASE_SECTIONS;
        }
    }

    /** The ticket types Chunky makes, from the static fields of its world class. Empty when the class is not there. */
    static Set<TicketType> ticketTypes() {
        Set<TicketType> out = new HashSet<>();
        for (String name : WORLD_CLASSES) {
            Class<?> world = Reflect.type(name);
            if (world == null) {
                continue;
            }
            for (Field f : world.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) && f.getType() == TicketType.class && f.trySetAccessible()) {
                    try {
                        if (f.get(null) instanceof TicketType type) {
                            out.add(type);
                        }
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        // not readable: that type is not recognised
                    }
                }
            }
        }
        return Set.copyOf(out);
    }

    /**
     * The configured count, or for 0 one from the processors, the heap and the world's height in sections. A request's heap grows
     * with the height. A world lower than vanilla's keeps the vanilla figure.
     */
    static int inFlight(int configured, int processors, long maxHeap, int sections) {
        if (configured > 0) {
            return configured;
        }
        long byHeap = maxHeap / (HEAP_PER_REQUEST * Math.max(sections, BASE_SECTIONS) / BASE_SECTIONS);
        long count = Math.min(Math.min((long) PER_PROCESSOR * processors, byHeap), MAX);
        return (int) Math.max(CHUNKY_DEFAULT, count);
    }

    /** The longest a request waits for room, so a task never hangs on requests that do not finish. */
    static final long MAX_WAIT_NANOS = TimeUnit.SECONDS.toNanos(30);
    /** Requests Chunky's task thread held back while the heap was nearly full. */
    public static final LongAdder HELD_BACK = new LongAdder();

    /**
     * On Chunky's task thread, before it takes a permit of {@code working} for its next request. While the heap is nearly full and
     * GA2 raised Chunky's count, waits until fewer than {@link #CHUNKY_DEFAULT} requests are open.
     */
    public static void waitForRoom(Semaphore working) {
        if (!ours || !MemoryPressure.high()) {
            return;
        }
        int max = Integer.getInteger(PROPERTY, CHUNKY_DEFAULT);
        if (!crowded(max, working.availablePermits())) {
            return;
        }
        HELD_BACK.increment();
        long until = System.nanoTime() + MAX_WAIT_NANOS;
        while (MemoryPressure.high() && crowded(max, working.availablePermits()) && System.nanoTime() < until) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Whether {@code max - available} open requests are as many as Chunky's own default or more. */
    static boolean crowded(int max, int available) {
        return max > CHUNKY_DEFAULT && max - available >= CHUNKY_DEFAULT;
    }
}
