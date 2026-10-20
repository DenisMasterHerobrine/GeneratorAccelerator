package dev.denismasterherobrine.generatoraccelerator2.view;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import javax.annotation.Nullable;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * Gives the VRAM of stale section meshes back to the driver.
 *
 * <p>Each render section owns one vertex buffer per block layer (solid, cutout, translucent and so on). Vanilla uploads a layer
 * only when the section has blocks in it. A layer that became empty keeps its last mesh in VRAM. The view area also moves its
 * sections along as the camera travels. A moved section keeps every mesh of its old place until it is rebuilt at the new one.
 * So each buffer holds the last mesh it ever had, and VRAM grows the further the player travels. Here such a buffer is emptied.
 * What is drawn is unchanged. Vanilla draws only the layers that a section's compiled state lists.
 */
public final class SectionBuffers {
    private SectionBuffers() {}

    private static final Logger LOGGER = Ga2Log.get("Render");

    private static volatile boolean logged;

    /** A section buffer with GA2's upload count and an empty-out. */
    public interface Buffer {
        /** Uploads into this buffer so far. */
        long ga2$uploads();

        /** Empties the buffer's storage (render thread). Returns the bytes given back. */
        long ga2$free();
    }

    /** A render section with the time it was last in view, and whether its meshes were evicted ({@link #evictHidden}). */
    public interface Section {
        long ga2$seen();

        void ga2$seen(long nanos);

        boolean ga2$evicted();

        void ga2$evicted(boolean evicted);
    }

    /**
     * Render thread, before the frame's sections are compiled. The sections in view are stamped. An evicted one is marked dirty,
     * so it is rebuilt this frame. About once a second, the sections out of view for {@link RenderThreads#EVICT_HIDDEN_SECONDS} and
     * farther than {@link RenderThreads#EVICT_MIN_CHUNKS} give their meshes back. Their compiled state stays. The occlusion graph
     * still passes through them, and their empty buffers draw nothing.
     */
    public static void evictHidden(Iterable<SectionRenderDispatcher.RenderSection> visible, @Nullable SectionRenderDispatcher.RenderSection[] all,
                                   Vec3 camera) {
        long now = System.nanoTime();
        for (SectionRenderDispatcher.RenderSection s : visible) {
            Section g = (Section) s;
            g.ga2$seen(now);
            if (g.ga2$evicted()) {
                g.ga2$evicted(false);
                s.setDirty(false);
            }
        }
        if (all == null || now - lastSweep < 1_000_000_000L) {
            return;
        }
        lastSweep = now;
        long hidden = RenderThreads.EVICT_HIDDEN_SECONDS * 1_000_000_000L;
        double min = RenderThreads.EVICT_MIN_CHUNKS * 16.0;
        long freed = 0;
        for (SectionRenderDispatcher.RenderSection s : all) {
            Section g = (Section) s;
            if (g.ga2$evicted() || now - g.ga2$seen() < hidden || s.getCompiled() == SectionRenderDispatcher.CompiledSection.UNCOMPILED) {
                continue;
            }
            BlockPos o = s.getOrigin();
            double dx = o.getX() + 8 - camera.x, dz = o.getZ() + 8 - camera.z;
            if (dx * dx + dz * dz < min * min) {
                continue;
            }
            long bytes = 0;
            for (RenderType layer : RenderType.chunkBufferLayers()) {
                bytes += ((Buffer) s.getBuffer(layer)).ga2$free();
            }
            if (bytes > 0) {
                g.ga2$evicted(true);
                freed += bytes;
            }
        }
        if (freed > 0) {
            VramStats.EVICTED.addAndGet(freed);
            if (!evictLogged) {
                evictLogged = true;
                LOGGER.info("meshes of sections out of view for {} s -> freed, rebuilt in view (-Dgeneratoraccelerator2.evictHiddenSections=0 keeps them)",
                    RenderThreads.EVICT_HIDDEN_SECONDS);
            }
        }
    }

    private static long lastSweep;
    private static boolean evictLogged;

    /** Empties {@code buffer} now on the render thread, else on the render thread's next frame if no upload came first. */
    public static void free(VertexBuffer buffer) {
        Buffer b = (Buffer) buffer;
        if (RenderSystem.isOnRenderThread()) {
            freed(b.ga2$free());
            return;
        }
        long uploads = b.ga2$uploads();
        RenderSystem.recordRenderCall(() -> {
            // A newer mesh was uploaded meanwhile. That mesh is in use.
            if (b.ga2$uploads() == uploads) {
                freed(b.ga2$free());
            }
        });
    }

    private static void freed(long bytes) {
        if (bytes <= 0) {
            return;
        }
        VramStats.FREED.addAndGet(bytes);
        if (!logged) {
            logged = true;
            LOGGER.info("stale section meshes -> freed (-Dgeneratoraccelerator2.freeSectionBuffers=false keeps them)");
        }
    }
}
