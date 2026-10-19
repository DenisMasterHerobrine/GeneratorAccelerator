package dev.denismasterherobrine.generatoraccelerator2.features.region;

import dev.denismasterherobrine.generatoraccelerator2.surface.BiomeZoom;

/**
 * Lets go of the generation regions this thread's reused fast-path objects still point at.
 *
 * <p>The per-thread section cursors ({@link SectionCursor#mine}) and biome zooms ({@link BiomeZoom}) keep the region they served
 * last. A region keeps its generation task's whole grid of chunk holders (23 x 23 around a chunk going to full status).
 * These references were kept on every thread that ran a step. They held hundreds of chunks in memory, each with its
 * sections and noise state, long after those chunks had been saved and unloaded. The step runners call {@link #release}
 * when a thread's outermost step ends.
 */
public final class RegionRefs {
    private RegionRefs() {}

    public static void release() {
        SectionCursor.releaseMine();
        BiomeZoom.releaseThread();
    }
}
