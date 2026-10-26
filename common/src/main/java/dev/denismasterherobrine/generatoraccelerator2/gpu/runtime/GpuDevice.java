package dev.denismasterherobrine.generatoraccelerator2.gpu.runtime;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.gpu.cl.ClApi;
import dev.denismasterherobrine.generatoraccelerator2.gpu.cl.ClDeviceInfo;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;

/**
 * One OpenCL device in use: its context, an in-order queue, and the thread that makes every OpenCL call of the GPU path ("GA2 GPU").
 * Work comes in as tasks and as pumps. Each {@link GpuWorldgen} launches its batches and collects finished ones in its pump. The
 * thread sleeps until new work arrives or an event completes. Completion callbacks wake it.
 */
public final class GpuDevice {
    static final Logger LOGGER = Ga2Log.get("GPU");

    /** Work the GPU thread does between waits. */
    public interface Pump {
        /** Launches and collects what it can. Returns how long the thread may sleep before calling again (0: call again now). */
        long pump(long now);
    }

    public final ClApi cl;
    public final ClDeviceInfo info;
    public final long context;
    public final long queue;
    /**
     * A second in-order queue for certified batches' double rechecks, packing and readback. This work overlaps the next batch's float
     * work.
     */
    public final long sideQueue;
    /**
     * A queue that stays idle. Mapping pinned host memory blocks until the queue's earlier work is done, so mapping must not run on
     * a busy queue.
     */
    public final long hostQueue;
    /**
     * Regions' shared work: corners, biomes and aquifer cells for 256 chunks at once. Batches need only their own region's work. On
     * this queue they do not wait behind other regions' work on the main queue. A batch waits for its region's event.
     */
    public final long regionQueue;
    /**
     * {@code -Dgeneratoraccelerator2.gpu.regionQueues=N} (default 3): the queues regions take in turn, {@link #regionQueue} first. With
     * one queue, regions ran one after another. A region's biomes then waited behind the previous region's corners while the device
     * was mostly idle.
     */
    public final long[] regionQueues;
    public final String binding;
    private final Thread thread;
    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final CopyOnWriteArrayList<Pump> pumps = new CopyOnWriteArrayList<>();
    private volatile boolean running = true;
    /** The thread has ended ({@link #close}). Tasks submitted after that run on the submitting thread. */
    private volatile boolean stopped;

    GpuDevice(ClApi cl, ClDeviceInfo info, String binding) {
        this.cl = cl;
        this.info = info;
        this.binding = binding;
        this.context = cl.createContext(info.platform(), info.device());
        this.queue = cl.createQueue(context, info.device(), Boolean.getBoolean("generatoraccelerator2.gpu.profile"));
        // -Dgeneratoraccelerator2.gpu.sideQueue=false uses one queue for everything (for comparisons).
        this.sideQueue = Boolean.parseBoolean(System.getProperty("generatoraccelerator2.gpu.sideQueue", "true"))
            ? cl.createQueue(context, info.device(), Boolean.getBoolean("generatoraccelerator2.gpu.profile")) : queue;
        this.hostQueue = cl.createQueue(context, info.device(), false);
        this.regionQueue = Boolean.parseBoolean(System.getProperty("generatoraccelerator2.gpu.regionQueue", "true"))
            ? cl.createQueue(context, info.device(), Boolean.getBoolean("generatoraccelerator2.gpu.profile")) : queue;
        int nRegion = regionQueue == queue ? 1 : Math.max(1, Integer.getInteger("generatoraccelerator2.gpu.regionQueues", 3));
        this.regionQueues = new long[nRegion];
        this.regionQueues[0] = regionQueue;
        for (int i = 1; i < nRegion; i++) {
            regionQueues[i] = cl.createQueue(context, info.device(), Boolean.getBoolean("generatoraccelerator2.gpu.profile"));
        }
        this.thread = new Thread(this::loop, "GA2 GPU");
        this.thread.setDaemon(true);
        cl.setWaker(this::wake);
        this.thread.start();
    }

    public void wake() {
        LockSupport.unpark(thread);
    }

    /** Runs {@code task} on the GPU thread, or on this thread once the device is closed. */
    public void submit(Runnable task) {
        tasks.add(task);
        if (stopped) {
            synchronized (tasks) {
                runTasks();
            }
        } else {
            wake();
        }
    }

    /**
     * Releases the queues and the context, then ends the GPU thread. Runs after the tasks submitted before. Worlds closed before
     * release their objects first. A world released later still can. The driver frees the context once its last object is released.
     */
    /** Code that releases objects of this device's context made elsewhere (the GPU light's), run first when the device closes. */
    private final List<Runnable> closers = new CopyOnWriteArrayList<>();

    /** Adds {@code closer} to the code run first when the device closes ({@link #close}). */
    public void onClose(Runnable closer) {
        closers.add(closer);
    }

    public void close() {
        submit(() -> {
            for (Runnable c : closers) {
                try {
                    c.run();
                } catch (RuntimeException e) {
                    LOGGER.warn("release on close failed", e);
                }
            }
            closers.clear();
            Set<Long> all = new LinkedHashSet<>();
            all.add(queue);
            all.add(sideQueue);
            all.add(hostQueue);
            for (long q : regionQueues) {
                all.add(q);
            }
            for (long q : all) {
                try {
                    cl.finish(q);
                } catch (RuntimeException ignored) {
                    // a lost device: the queue goes with the context
                }
            }
            for (long q : all) {
                cl.releaseQueue(q);
            }
            cl.releaseContext(context);
            pumps.clear();
            running = false;
            LOGGER.info("closed: {}", info.name().trim());
        });
    }

    private void runTasks() {
        Runnable t;
        while ((t = tasks.poll()) != null) {
            try {
                t.run();
            } catch (Throwable e) {
                LOGGER.error("task failed", e);
            }
        }
    }

    public void addPump(Pump pump) {
        pumps.add(pump);
        wake();
    }

    public void removePump(Pump pump) {
        pumps.remove(pump);
    }

    private void loop() {
        while (running) {
            runTasks();
            if (!running) {
                break;
            }
            long wait = 50_000_000L;
            for (Pump p : pumps) {
                try {
                    wait = Math.min(wait, p.pump(System.nanoTime()));
                } catch (Throwable e) {
                    LOGGER.error("pump failed", e);
                    pumps.remove(p);
                }
            }
            if (wait > 0 && tasks.isEmpty()) {
                LockSupport.parkNanos(this, wait);
            }
        }
        // A task submitted while the thread ended runs here or on its submitting thread.
        stopped = true;
        synchronized (tasks) {
            runTasks();
        }
    }

    public String describe() {
        return info.describe() + ", " + binding;
    }

    // ---- tests: a slower GPU ----

    /**
     * {@code -Dgeneratoraccelerator2.gpu.debugSpinUs=N} (tests): about N µs more device time per chunk, as a slower GPU would take.
     * Regions, batches and surface batches each put a kernel that fills the device for their share before their work.
     */
    public static final double DEBUG_SPIN_US = Double.parseDouble(System.getProperty("generatoraccelerator2.gpu.debugSpinUs", "0"));
    private static final String SPIN_SOURCE = "__kernel void k_spin(__global uint* sink, uint n) {"
        + " uint x = get_global_id(0) * 2654435761u + 1u;"
        + " for (uint i = 0; i < n; i++) { x = x * 1664525u + 1013904223u; }"
        + " if (x == 0x9e3779b9u) { sink[0] = x; } }";
    private long spinProgram, spinKernel, spinSink, spinGlobal;
    private double spinItersPerUs;

    /** Puts {@code chunks} chunks' share of {@link #DEBUG_SPIN_US} on {@code queue} (GPU thread). */
    public void debugSpin(long queue, double chunks) {
        if (DEBUG_SPIN_US <= 0 || chunks <= 0) {
            return;
        }
        if (spinKernel == 0) {
            spinProgram = cl.buildProgram(context, info.device(), SPIN_SOURCE, "", new StringBuilder());
            spinKernel = cl.createKernel(spinProgram, "k_spin");
            spinSink = cl.createBuffer(context, ClApi.MEM_READ_WRITE, 16);
            // more work-items than the device holds at once, so the kernel leaves no room beside it
            spinGlobal = (long) info.computeUnits() * 2048;
            cl.argMem(spinKernel, 0, spinSink);
            int n = 1 << 18;
            spin(hostQueue, n);
            cl.finish(hostQueue);
            long t0 = System.nanoTime();
            spin(hostQueue, n);
            cl.finish(hostQueue);
            spinItersPerUs = n / Math.max(1.0, (System.nanoTime() - t0) / 1000.0);
            closers.add(() -> {
                cl.releaseKernel(spinKernel);
                cl.releaseProgram(spinProgram);
                cl.releaseMem(spinSink);
            });
            LOGGER.info("debug spin: {} iterations per µs -> {} µs more device time per chunk", String.format(Locale.ROOT, "%.1f", spinItersPerUs), DEBUG_SPIN_US);
        }
        spin(queue, (int) Math.min(Integer.MAX_VALUE, Math.round(chunks * DEBUG_SPIN_US * spinItersPerUs)));
    }

    private void spin(long queue, int n) {
        cl.argInt(spinKernel, 1, n);
        cl.releaseEvent(cl.launch(queue, spinKernel, spinGlobal, 64));
    }
}
