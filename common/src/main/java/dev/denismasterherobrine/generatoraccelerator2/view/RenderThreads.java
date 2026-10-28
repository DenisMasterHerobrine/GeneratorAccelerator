package dev.denismasterherobrine.generatoraccelerator2.view;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;

/**
 * Own threads for the client's section meshes and its section occlusion graph.
 *
 * <p>Vanilla builds both on the shared worker pool. The integrated server generates, lights and loads chunks on that
 * same pool. A pool worker drains its own queue before it takes work from outside. So while the server keeps the pool
 * busy, a mesh or a graph rebuild waits behind the server's tasks. A stale graph comes from an old camera position. It
 * stops at sections that are not meshed yet, and the sections behind them drop out of view until the next rebuild.
 * Sections then vanish and come back as the camera moves. Here meshes and graph rebuilds run on threads of their own.
 * What runs, and its results, are unchanged.
 */
public final class RenderThreads {
    private RenderThreads() {}

    private static final Logger LOGGER = Ga2Log.get("Render");

    /** {@code -Dgeneratoraccelerator2.renderThreads=false} keeps vanilla's shared pool. */
    public static final boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.renderThreads"));
    /**
     * {@code -Dgeneratoraccelerator2.freeSectionBuffers=false} keeps stale section meshes in VRAM as vanilla does ({@code SectionBuffers}).
     * Here and not there, because the mixin plugin reads it before the client's classes may load.
     */
    public static final boolean FREE_SECTION_BUFFERS = !"false".equals(System.getProperty("generatoraccelerator2.freeSectionBuffers"));
    /**
     * {@code -Dgeneratoraccelerator2.evictHiddenSections=N}: a section out of view for N seconds gives its meshes' VRAM back, and is
     * rebuilt once it is in view again ({@code SectionBuffers#evictHidden}). 0 keeps every mesh as vanilla does. While the player
     * travels, a section behind them is reused for a place ahead within a few seconds. So the default is short. With 10 s, flight
     * at 80 blocks/s evicted nothing.
     */
    public static final long EVICT_HIDDEN_SECONDS = Long.getLong("generatoraccelerator2.evictHiddenSections", 3L);
    /** {@code -Dgeneratoraccelerator2.evictHiddenSections.minChunks=N}: sections closer than N chunks to the camera keep their meshes. */
    public static final int EVICT_MIN_CHUNKS = Integer.getInteger("generatoraccelerator2.evictHiddenSections.minChunks", 8);
    /**
     * {@code -Dgeneratoraccelerator2.evictHiddenSections.travelChunks=N}: a section keeps its meshes until the camera is N chunks away
     * from where it last saw the section. A player who stands and looks around evicts nothing, so turning back rebuilds nothing.
     * Flight at 80 blocks/s covers 4 chunks in under a second, well inside the hidden time.
     */
    public static final int EVICT_TRAVEL_CHUNKS = Integer.getInteger("generatoraccelerator2.evictHiddenSections.travelChunks", 4);
    private static volatile ExecutorService mesh;
    private static volatile ExecutorService graph;

    /** The executor for section meshes. */
    public static ExecutorService mesh(ExecutorService vanilla) {
        if (!ENABLED) {
            return vanilla;
        }
        ExecutorService e = mesh;
        if (e == null) {
            synchronized (RenderThreads.class) {
                e = mesh;
                if (e == null) {
                    int threads = meshThreads();
                    int priority = workerPriority();
                    e = pool("GA2 Section Mesh", threads, priority);
                    mesh = e;
                    LOGGER.info("section meshes: {} own threads at priority {} -> on (-Dgeneratoraccelerator2.renderThreads=false for the shared pool)", threads, priority);
                }
            }
        }
        return e;
    }

    /**
     * The executor for full rebuilds of the section occlusion graph. One rebuild runs at a time. Its one thread runs above the
     * worker pool. A rebuild starts from the camera of its start. The longer it takes, the further the camera has moved when
     * it is shown. It takes at most one processor thread, so the server thread keeps the others.
     */
    public static ExecutorService graph(ExecutorService vanilla) {
        if (!ENABLED) {
            return vanilla;
        }
        ExecutorService e = graph;
        if (e == null) {
            synchronized (RenderThreads.class) {
                e = graph;
                if (e == null) {
                    int priority = Math.max(workerPriority(), Thread.NORM_PRIORITY + 1);
                    e = pool("GA2 Section Graph", 1, priority);
                    graph = e;
                    LOGGER.info("visibility graph: own thread at priority {}", priority);
                }
            }
        }
        return e;
    }

    /** Half the processor threads, from 1 to 8. {@code -Dgeneratoraccelerator2.renderThreads.mesh=N} sets the count. */
    private static int meshThreads() {
        int fallback = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() / 2));
        try {
            int n = Integer.parseInt(System.getProperty("generatoraccelerator2.renderThreads.mesh", "0"));
            return n > 0 ? Math.min(n, 64) : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /**
     * The priority of the shared worker pool's threads, where vanilla meshes. Mods may change it. ModernFix runs the pool and
     * the integrated server below normal priority. Meshing above them would take the processor from the server thread while
     * the camera moves, and chunk generation would stall.
     */
    private static int workerPriority() {
        int priority = 0;
        try {
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t.getName().startsWith("Worker-Main-")) {
                    priority = Math.max(priority, t.getPriority());
                }
            }
        } catch (SecurityException e) {
            // no access to the other threads
        }
        return priority > 0 ? priority : Thread.NORM_PRIORITY;
    }

    /** Daemon threads that exit after 30 s without work. */
    private static ExecutorService pool(String name, int threads, int priority) {
        AtomicInteger ids = new AtomicInteger();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
            Thread thread = new Thread(task, name + " #" + ids.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(priority);
            thread.setUncaughtExceptionHandler((t, ex) -> LOGGER.error("uncaught exception on {}", t.getName(), ex));
            return thread;
        });
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
