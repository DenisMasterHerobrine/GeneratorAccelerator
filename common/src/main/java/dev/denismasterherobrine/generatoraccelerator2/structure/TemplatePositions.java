package dev.denismasterherobrine.generatoraccelerator2.structure;

import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
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
 *
 * <p>Templates of different ids load at the same time ({@link TemplateLoads}), so the table is split into stripes by position,
 * each with its own lock.
 */
public final class TemplatePositions {
    private TemplatePositions() {}

    public static final boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.templatePositions"));
    private static final int MAX = 1 << 21;
    private static final int STRIPES = 64;
    private static final Stripe[] TABLE = new Stripe[STRIPES];

    static {
        for (int i = 0; i < STRIPES; i++) {
            TABLE[i] = new Stripe();
        }
    }

    /** A part of the table, and its counts. Read and written under its own lock. */
    private static final class Stripe {
        final Long2ObjectOpenHashMap<BlockPos> positions = new Long2ObjectOpenHashMap<>(1 << 8);
        long loaded;
        long shared;
    }

    /** The position {@code (x, y, z)}, shared with every template block at the same position. Templates load on few threads, rarely. */
    public static BlockPos of(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        Stripe stripe = TABLE[(int) HashCommon.mix(key) & (STRIPES - 1)];
        synchronized (stripe) {
            stripe.loaded++;
            BlockPos pos = stripe.positions.get(key);
            if (pos != null) {
                stripe.shared++;
                return pos;
            }
            pos = new BlockPos(x, y, z);
            if (stripe.positions.size() < MAX / STRIPES) {
                stripe.positions.put(key, pos);
            }
            return pos;
        }
    }

    /** Template blocks whose position was asked here, those given a shared position, and the distinct positions kept. */
    public static String counts() {
        long loaded = 0, shared = 0, distinct = 0;
        for (Stripe stripe : TABLE) {
            synchronized (stripe) {
                loaded += stripe.loaded;
                shared += stripe.shared;
                distinct += stripe.positions.size();
            }
        }
        return "template positions shared " + shared + " of " + loaded + " loaded (" + distinct + " distinct)";
    }
}
