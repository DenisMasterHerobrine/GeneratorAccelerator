package dev.denismasterherobrine.generatoraccelerator2.structure;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;

/**
 * One {@code BlockPos} per position across every loaded structure template.
 *
 * <p>A template keeps a {@code StructureBlockInfo} per block, each with its own {@code BlockPos}. Templates stay loaded for the
 * whole session, and a large pack holds millions of their blocks (4.5 million in a 575-mod pack). Their positions are
 * template-local, so a few hundred thousand distinct positions cover them all. {@code BlockPos} is immutable and compared by
 * value, so sharing one object per position changes nothing the game can observe and saves 24 bytes per block (about
 * 100 MB in that pack). The table stops growing at {@link #MAX} entries, after which positions are made as before.
 * {@code -Dgeneratoraccelerator2.templatePositions=false} turns it off.
 */
public final class TemplatePositions {
    private TemplatePositions() {}

    public static final boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.templatePositions"));
    private static final int MAX = 1 << 21;
    private static final Long2ObjectOpenHashMap<BlockPos> TABLE = new Long2ObjectOpenHashMap<>(1 << 14);
    public static final LongAdder LOADED = new LongAdder();
    public static final LongAdder SHARED = new LongAdder();

    /** The position {@code (x, y, z)}, shared with every template block at the same position. Templates load on few threads, rarely. */
    public static BlockPos of(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        LOADED.increment();
        synchronized (TABLE) {
            BlockPos pos = TABLE.get(key);
            if (pos != null) {
                SHARED.increment();
                return pos;
            }
            pos = new BlockPos(x, y, z);
            if (TABLE.size() < MAX) {
                TABLE.put(key, pos);
            }
            return pos;
        }
    }

    public static int distinct() {
        synchronized (TABLE) {
            return TABLE.size();
        }
    }
}
