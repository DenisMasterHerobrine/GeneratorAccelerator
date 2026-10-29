package dev.denismasterherobrine.generatoraccelerator2.scheduling;

import java.util.function.Supplier;
import dev.denismasterherobrine.generatoraccelerator2.features.region.RegionRefs;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * Thread markers for the chunk step hooks.
 *
 * <p>The mailbox marker is set on the thread running vanilla's chunk generation task loop
 * ({@code ChunkGenerationTask.runUntilWait}, always on the single worldgen mailbox). Steps only move to the
 * worker pool from there. Under another chunk system (Moonrise, a C2ME scheduler), the step is already on
 * that system's own thread, so it runs inline.
 *
 * <p>The vanilla marker is set while a worker runs the original step method that a hook handed to the
 * pool. The hook lets that call through.
 */
public final class StepThreads {
    private StepThreads() {}

    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);
    private static final ThreadLocal<int[]> VANILLA = ThreadLocal.withInitial(() -> new int[1]);
    /** The blender a parallel surface step computed on the worldgen thread (see {@code SurfaceBlenderMixin}). */
    private static final ThreadLocal<Blender[]> BLENDER =
        ThreadLocal.withInitial(() -> new Blender[1]);

    /**
     * Runs {@code body} with {@code blender} standing in for {@code Blender.of(region)} in the generator's surface step. The thread's slot
     * is kept between steps. Setting and removing a thread local each time allocated a map entry per surface.
     */
    public static void withBlender(Blender blender, Runnable body) {
        Blender[] slot = BLENDER.get();
        Blender previous = slot[0];
        slot[0] = blender;
        try {
            body.run();
        } finally {
            slot[0] = previous;
        }
    }

    public static Blender stepBlender() {
        return BLENDER.get()[0];
    }

    /** Serializes step bodies that run after the server stopped (see {@link #guarded}). */
    private static final Object STOPPED = new Object();

    public static boolean onVanillaMailbox() {
        return DEPTH.get()[0] > 0;
    }

    /**
     * Runs vanilla's generation task loop. Steps that run inline (on this thread) also use the fast paths' reused objects. Those
     * objects let go of their region when the loop returns
     * ({@link RegionRefs}).
     */
    public static <T> T runOnMailbox(Supplier<T> body) {
        int[] depth = DEPTH.get();
        depth[0]++;
        try {
            return body.get();
        } finally {
            if (--depth[0] == 0) {
                RegionRefs.release();
            }
        }
    }

    /** True while the original step method runs on behalf of a hook. */
    public static boolean runningVanilla() {
        return VANILLA.get()[0] > 0;
    }

    /** Runs the original step method. Meanwhile, the step hooks let calls on this thread through. */
    public static <T> T vanilla(Supplier<T> body) {
        int[] depth = VANILLA.get();
        depth[0]++;
        try {
            return body.get();
        } finally {
            depth[0]--;
        }
    }

    /**
     * Runs a step body on a worker. After the server stops, {@code MinecraftServer.execute} runs posted tasks
     * (point of interest changes, structure checks) on the caller instead of the main thread. So step bodies
     * that still finish then run one at a time, as they would on vanilla's single worldgen thread.
     */
    public static <T> T guarded(MinecraftServer server, Supplier<T> body) {
        if (server.isRunning()) {
            return body.get();
        }
        synchronized (STOPPED) {
            return body.get();
        }
    }

    /**
     * {@code body} with the thread named {@code name} while it runs, in development runs only, as vanilla named its step threads
     * before 1.21.2.
     */
    /** {@link #named(String, Supplier)} for a task without a result. */
    public static Runnable named(String name, Runnable body) {
        Supplier<Void> named = named(name, () -> {
            body.run();
            return null;
        });
        return SharedConstants.IS_RUNNING_IN_IDE ? named::get : body;
    }

    public static <T> Supplier<T> named(String name, Supplier<T> body) {
        if (!SharedConstants.IS_RUNNING_IN_IDE) {
            return body;
        }
        return () -> {
            Thread thread = Thread.currentThread();
            String old = thread.getName();
            thread.setName(name);
            try {
                return body.get();
            } finally {
                thread.setName(old);
            }
        };
    }
}
