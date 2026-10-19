package dev.denismasterherobrine.generatoraccelerator2.gpu.decor;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Every block write into one chunk that the GPU keeps a copy of, from the chunk's surface on. {@code SectionDeltaMixin} records each
 * {@code LevelChunkSection.setBlockState} that changes a block. The GPU's copy is brought up to date from this log, with each step's
 * blocks, or with a whole section where the step wrote much of it. A GPU result that read a block written after its snapshot is not
 * used.
 *
 * <p>Writes to one chunk come from one step at a time: the chunk's own CARVERS, or the FEATURES of the chunks around it, which the
 * area locks keep apart. So appends need no lock. A step publishes its appends when it ends ({@link #publish}).
 */
public final class DeltaLog {
    /** Set on entries written from a GPU result, which the GPU's own copy had. Not a change for the results after that result. */
    public static final int EXPECTED = 1 << 31;

    public final ChunkPos pos;
    public final StateIds ids;
    public final GpuDecor owner;
    private int[] where = new int[512];
    private short[] state = new short[512];
    /** Each entry's state before the write (the checks of a GPU result replay the GPU's writes over the states before them). */
    private short[] old = new short[512];
    private int size;
    /** Entries the GPU has (sent to it). */
    private int sent;
    private volatile int published;
    /** Entries queued for the GPU, set after they are queued. A job requested after reading this value finds them on the GPU. */
    public volatile int sentIndex;
    /** Set when the chunk is no longer kept on the GPU (dropped, or a write the GPU's copy cannot hold). Recording stops. */
    public volatile boolean closed;
    /** The distinct biomes stored in the chunk, once asked. They never change from the chunk's BIOMES step on. */
    public volatile Object[] biomes;
    /** Set by the decoration writing into this chunk while its writes come from a GPU result or are checked against one. */
    public boolean committing;

    public DeltaLog(ChunkPos pos, StateIds ids, GpuDecor owner) {
        this.pos = pos;
        this.ids = ids;
        this.owner = owner;
    }

    /** {@code SectionDeltaMixin}: a changed block (section index, x, y, z in the section). */
    public void record(int section, int x, int y, int z, BlockState now, BlockState before) {
        if (closed) {
            return;
        }
        int id = ids.id(now), was = ids.id(before);
        if (id < 0 || was < 0) {
            closed = true;
            return;
        }
        if (size == where.length) {
            where = java.util.Arrays.copyOf(where, size * 2);
            state = java.util.Arrays.copyOf(state, size * 2);
            old = java.util.Arrays.copyOf(old, size * 2);
        }
        int w = section << 12 | y << 8 | z << 4 | x;
        where[size] = committing ? w | EXPECTED : w;
        state[size] = (short) id;
        old[size] = (short) was;
        size++;
    }


    /** Ends a step that wrote. The step's appends become visible to other threads. */
    public void publish() {
        published = size;
    }

    public int size() {
        return published;
    }

    /** Every entry so far, published or not (the writing thread's view). */
    public int sizeNow() {
        return size;
    }

    public int where(int i) {
        return where[i];
    }

    /** The entries' positions as kept (valid up to {@link #sizeNow}, in the writing thread's view). */
    int[] whereArray() {
        return where;
    }

    public int state(int i) {
        return state[i] & 0xFFFF;
    }

    /** The state an entry's write replaced. */
    public int old(int i) {
        return old[i] & 0xFFFF;
    }

    /** The entries handed to the GPU thread so far (the flushing thread's count). */
    public int handedOut() {
        return sent;
    }

    public void handOut(int to) {
        sent = to;
    }
}
