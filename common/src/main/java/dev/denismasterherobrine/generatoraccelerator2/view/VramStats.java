package dev.denismasterherobrine.generatoraccelerator2.view;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** VRAM counts the client keeps (F3). This class names no client class. */
public final class VramStats {
    private VramStats() {}

    /** Bytes every vertex buffer holds now. Chunk meshes are nearly all of it. */
    public static final LongAdder HELD = new LongAdder();
    /** Bytes of stale chunk meshes given back ({@code SectionBuffers}). */
    public static final AtomicLong FREED = new AtomicLong();
    /** Bytes of meshes given back by sections long out of view ({@code SectionBuffers#evictHidden}). */
    public static final AtomicLong EVICTED = new AtomicLong();
}
