package dev.denismasterherobrine.generatoraccelerator2.chunkio;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.lithium.LithiumSections;
import dev.denismasterherobrine.generatoraccelerator2.mixin.dfc.PalettedContainerInvoker;
import java.util.Arrays;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * The noise fill's block writes into a chunk whose sections are all air. The writes are collected per section as small
 * local ids, together with their write order. Each section's storage is packed once at the end. This replaces one
 * {@code LevelChunkSection.setBlockState} per block, which costs a palette lookup, a bit-storage swap, the occasional
 * palette resize and four state checks for the counters.
 *
 * <p>The result is what the per-block writes leave in memory. Vanilla's first write into an all-air section resizes it to
 * a linear palette. That palette holds air, then each state in the order it is first written. A section of at most 16
 * states (air included) never resizes again. Its palette is therefore built that way at once, and its storage is packed
 * from the ids. The counters are set to the values {@code setBlockState} reaches from air: {@code nonEmptyBlockCount} for
 * every non-empty state, {@code tickingBlockCount} for states that tick randomly, {@code tickingFluidCount} for every
 * state with a fluid. A section with more states would resize again, which reorders the palette by position. Its writes
 * are therefore replayed through {@code setBlockState} in their original order. Nothing reads the chunk's blocks during
 * the fill, so deferring the writes changes nothing else. If another mod changes {@code LevelChunkSection.setBlockState},
 * the per-block writes are kept ({@link HookTargets#NOISE_SECTION_WRITES}).
 */
public final class SectionWriter {
    /** {@code -Dgeneratoraccelerator2.sectionWrites=false} writes block by block. */
    public static final boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.sectionWrites"));
    private static final HookTargets.Hook HOOK = HookTargets.hook(HookTargets.NOISE_SECTION_WRITES);
    private static final ThreadLocal<SectionWriter> CACHE = ThreadLocal.withInitial(SectionWriter::new);
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    /** The most entries (air included) a linear palette holds before vanilla resizes it. */
    private static final int LINEAR_ENTRIES = 16;

    /**
     * Palette data for {@code size} entries, of the kind a section written block by block grows into from {@code from} (null: a
     * section of one value). A palette that still has room keeps its kind and entries (a copy). A full one resizes: from one value the
     * container's strategy is asked for 1 bit, after that for one bit more than the full palette has. Asking for {@code ceillog2(size)}
     * bits at once gives the same with vanilla's strategy (a linear palette of 4 bits up to 16 entries). Lithium's strategy gives 3 and
     * 4 bits its own hash palette, so the chain is followed instead.
     */
    @SuppressWarnings("unchecked")
    public static PalettedContainer.Data<BlockState> grownData(PalettedContainer<BlockState> container, @Nullable PalettedContainer.Data<BlockState> from, int size) {
        int fromBits = from == null ? 0 : storageBits(from);
        if (from != null && size <= 1 << fromBits) {
            return from.copy();
        }
        PalettedContainerInvoker invoker = (PalettedContainerInvoker) (Object) container;
        int bits = fromBits > 0 ? fromBits + 1 : size <= 1 ? 0 : 1;
        PalettedContainer.Data<BlockState> data = invoker.ga2$createOrReuseData(null, bits);
        // a palette over a storage of b bits holds 1 << b entries
        while (bits > 0 && size > 1 << storageBits(data)) {
            bits = storageBits(data) + 1;
            data = invoker.ga2$createOrReuseData(null, bits);
        }
        return data;
    }

    /** The bits of the data's storage, 0 for a single value. */
    private static int storageBits(PalettedContainer.Data<BlockState> data) {
        return data.storage() instanceof SimpleBitStorage storage ? storage.getBits() : 0;
    }
    public static final LongAdder CHUNKS = new LongAdder();
    public static final LongAdder SECTIONS = new LongAdder();
    /** Sections of more than 16 states, replayed block by block. */
    public static final LongAdder REPLAYED = new LongAdder();
    /** {@code -Dgeneratoraccelerator2.verify.sectionWrites=N} reads every N-th packed section back block by block. */
    private static final VerifyCounter VERIFY = new VerifyCounter("sectionWrites");

    private LevelChunkSection[] sections;
    /** Per section: local ids by block index {@code y << 8 | z << 4 | x}. 0 is air (nothing written). */
    private short[][] ids = new short[0][];
    /** Per section: the written block indices in write order. {@link #counts} holds their number. */
    private short[][] logs = new short[0][];
    private int[] counts = new int[0];
    private boolean[] touched = new boolean[0];
    private int current = -1;
    private short[] curIds;
    private short[] curLog;
    private int curCount;
    /** Local palette. Entry 0 is air. */
    private BlockState[] states = new BlockState[16];
    private int count;
    private BlockState lastState;
    private short lastId;
    private int[] hist = new int[16];
    private int[] map = new int[16];
    private short[] order = new short[16];

    private SectionWriter() {}

    /** This thread's writer for {@code chunk}, or null when its writes must go block by block. */
    public static SectionWriter begin(ChunkAccess chunk) {
        if (!ENABLED || !HOOK.allowed()) {
            return null;
        }
        LevelChunkSection[] secs = chunk.getSections();
        for (LevelChunkSection section : secs) {
            if (section.getStates().data.palette().maybeHas(s -> s != AIR)) {
                return null;
            }
        }
        SectionWriter w = CACHE.get();
        if (w.sections != null) {
            return null; // A fill is already running on this thread.
        }
        w.sections = secs;
        if (w.ids.length < secs.length) {
            w.ids = Arrays.copyOf(w.ids, secs.length);
            w.logs = Arrays.copyOf(w.logs, secs.length);
            w.counts = new int[secs.length];
            w.touched = new boolean[secs.length];
        } else {
            Arrays.fill(w.touched, false);
        }
        w.current = -1;
        w.states[0] = AIR;
        w.count = 1;
        w.lastState = AIR;
        w.lastId = 0;
        return w;
    }

    /** Makes section {@code index} the target of {@link #put}. Its buffers are cleared on first use for this chunk. */
    public void select(int index) {
        if (index == current) {
            return;
        }
        if (current >= 0) {
            counts[current] = curCount;
        }
        if (!touched[index]) {
            if (ids[index] == null) {
                ids[index] = new short[4096];
                logs[index] = new short[4096];
            } else {
                Arrays.fill(ids[index], (short) 0);
            }
            counts[index] = 0;
            touched[index] = true;
        }
        current = index;
        curIds = ids[index];
        curLog = logs[index];
        curCount = counts[index];
    }

    /** Writes local id {@code id} at section index {@code index} ({@code y << 8 | z << 4 | x}) of the selected section. */
    public void put(int index, short id) {
        curIds[index] = id;
        curLog[curCount++] = (short) index;
    }

    /** The local id of {@code state}. */
    public short id(BlockState state) {
        if (state == lastState) {
            return lastId;
        }
        for (int i = 0; i < count; i++) {
            if (states[i] == state) {
                lastState = state;
                lastId = (short) i;
                return (short) i;
            }
        }
        if (count == states.length) {
            states = Arrays.copyOf(states, count * 2);
        }
        states[count] = state;
        lastState = state;
        lastId = (short) count;
        return (short) count++;
    }

    /** Packs every written section into its storage and sets its counters. */
    public void finish() {
        if (current >= 0) {
            counts[current] = curCount;
        }
        if (hist.length < count) {
            hist = new int[states.length];
            map = new int[states.length];
        }
        for (int s = 0; s < sections.length; s++) {
            if (touched[s] && counts[s] > 0) {
                pack(sections[s], ids[s], logs[s], counts[s]);
            }
        }
        CHUNKS.increment();
    }

    /** Ends this chunk, after {@link #finish} or when the fill failed. The writer can then begin the next chunk. */
    public void close() {
        sections = null;
        Arrays.fill(states, 1, count, null);
        count = 1;
        lastState = null;
        current = -1;
        curIds = null;
        curLog = null;
    }

    private void pack(LevelChunkSection section, short[] a, short[] log, int written) {
        SECTIONS.increment();
        int n = count;
        // The states in the order they were first written. hist marks the states already met.
        Arrays.fill(hist, 0, n, 0);
        int distinct = 0;
        for (int w = 0; w < written; w++) {
            short id = a[log[w]];
            // Writing air (a default block of air) leaves the palette unchanged, as vanilla's write does.
            if (hist[id]++ == 0 && id != 0) {
                if (distinct == order.length) {
                    order = Arrays.copyOf(order, distinct * 2);
                }
                order[distinct++] = id;
            }
        }
        if (distinct == 0) {
            return; // Only air was written. The section stays all air.
        }
        if (distinct + 1 > LINEAR_ENTRIES || !direct(distinct)) {
            replay(section, a, log, written);
            return;
        }
        PalettedContainer<BlockState> container = section.getStates();
        // Vanilla's palette after the first write: air, then each state as first written.
        // Built at once, so no resize copies air.
        PalettedContainer.Data<BlockState> data = grownData(container, null, distinct + 1);
        Palette<BlockState> palette = data.palette();
        map[0] = palette.idFor(AIR);
        for (int i = 0; i < distinct; i++) {
            map[order[i]] = palette.idFor(states[order[i]]);
        }
        BitStorage storage = data.storage();
        if (palette.getSize() != distinct + 1 || !(storage instanceof SimpleBitStorage)) {
            throw new IllegalStateException("section palette of " + palette.getSize() + " entries for " + (distinct + 1) + " states");
        }
        long[] raw = storage.getRaw();
        int bits = storage.getBits();
        int perLong = 64 / bits;
        int index = 0;
        for (int w = 0; w < raw.length; w++) {
            long word = 0L;
            int end = Math.min(index + perLong, 4096);
            for (int j = 0; index < end; j++, index++) {
                word |= (long) map[a[index]] << (j * bits);
            }
            raw[w] = word;
        }
        container.data = data;
        int nonEmpty = 0;
        int ticking = 0;
        int fluids = 0;
        for (int i = 0; i < distinct; i++) {
            int c = hist[order[i]];
            BlockState state = states[order[i]];
            if (!state.isAir()) {
                nonEmpty += c;
                if (state.isRandomlyTicking()) {
                    ticking += c;
                }
            }
            if (!state.getFluidState().isEmpty()) {
                fluids += c;
            }
        }
        section.nonEmptyBlockCount = (short) (section.nonEmptyBlockCount + nonEmpty);
        section.tickingBlockCount = (short) (section.tickingBlockCount + ticking);
        section.tickingFluidCount = (short) (section.tickingFluidCount + fluids);
        LithiumSections.forgetCounts(section);
        if (VERIFY.sample()) {
            verify(section, a);
        }
    }

    /** Whether the section's first {@code distinct} states may be written without {@code setBlockState} ({@link SectionWrites}). */
    private boolean direct(int distinct) {
        if (!SectionWrites.filtered()) {
            return true;
        }
        for (int i = 0; i < distinct; i++) {
            if (!SectionWrites.direct(states[order[i]])) {
                return false;
            }
        }
        return true;
    }

    /** Replays the section's writes through {@code setBlockState}, in the order the fill made them. */
    private void replay(LevelChunkSection section, short[] a, short[] log, int written) {
        REPLAYED.increment();
        for (int w = 0; w < written; w++) {
            int index = log[w];
            section.setBlockState(index & 15, index >> 8, (index >> 4) & 15, states[a[index]], false);
        }
    }

    private void verify(LevelChunkSection section, short[] a) {
        int nonEmpty = 0;
        int ticking = 0;
        int fluids = 0;
        for (int i = 0; i < 4096; i++) {
            BlockState got = section.getBlockState(i & 15, i >> 8, (i >> 4) & 15);
            if (got != states[a[i]]) {
                VERIFY.result(false, "index " + i, states[a[i]], got);
                return;
            }
            // The counts setBlockState reaches when writing over air.
            if (!got.isAir()) {
                nonEmpty++;
                if (got.isRandomlyTicking()) {
                    ticking++;
                }
            }
            if (!got.getFluidState().isEmpty()) {
                fluids++;
            }
        }
        VERIFY.result(nonEmpty == section.nonEmptyBlockCount && ticking == section.tickingBlockCount && fluids == section.tickingFluidCount,
            "counters", nonEmpty + "/" + ticking + "/" + fluids, section.nonEmptyBlockCount + "/" + section.tickingBlockCount + "/" + section.tickingFluidCount);
    }
}
