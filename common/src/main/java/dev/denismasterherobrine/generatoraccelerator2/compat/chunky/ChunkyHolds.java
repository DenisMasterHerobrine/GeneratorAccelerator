package dev.denismasterherobrine.generatoraccelerator2.compat.chunky;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.dfc.Compat;
import dev.denismasterherobrine.generatoraccelerator2.memory.MemoryPressure;
import dev.denismasterherobrine.generatoraccelerator2.scheduling.SchedulingConfig;
import it.unimi.dsi.fastutil.longs.Long2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/**
 * Keeps the chunks next to Chunky's requests loaded while Chunky will still need them. One per dimension, on the server thread.
 *
 * <p>Chunky asks for one chunk at a time. It adds a ticket at the full chunk level and removes it once the chunk is full. A
 * full chunk needs the chunks within the radius (11) at earlier steps, and the ticket keeps them loaded while it lasts.
 * Chunky's default pattern goes region by region: a Hilbert curve inside each region, the regions in a spiral. Along the edges
 * of a region the chunks of the next regions are generated part of the way. Once no ticket covers them, vanilla saves and
 * unloads them, and the next region reads them back. The full chunks beside them are read back too, as neighbours. Vanilla does
 * both on the server thread: a read decodes the whole saved chunk and a save encodes it. A simulation of Chunky 1.4.23's order
 * with 256 open requests over 251,001 chunks read each chunk back 1.87 times, 0.88 of them as a full chunk. It also saved 0.6
 * chunks per chunk that were not full yet.
 *
 * <p>When a Chunky ticket goes, a chunk left without any Chunky ticket within the radius gets GA2's own ticket, unless Chunky
 * has already asked for every chunk within the radius of it. The ticket is at the last loaded level ({@code ChunkLevel.MAX_LEVEL},
 * 44). It keeps the chunk with its finished steps. It reaches no neighbour and starts no generation. The chunk is let go once
 * Chunky has asked for every chunk within the radius of it. Chunks near the edge of Chunky's selection, or next to chunks Chunky
 * skips, never get there. The cap lets them go: past it, the chunk a Chunky request came near least recently goes first. With
 * 4,096 kept, the simulation read each chunk back 0.67 times (0.32 as a full chunk) and saved 0.34 chunks that were not full.
 * Most of the rest waits a whole round of the spiral, more than a cap can keep. About 42% of the kept chunks were full and 42%
 * at their first step (structure starts).
 *
 * <p>Nothing is saved differently. A chunk that is let go is saved and unloaded the vanilla way. A kept chunk goes on from memory
 * instead of from its saved copy, as it does whenever a Chunky ticket still covers it. Every tick vanilla also saves up to 20
 * loaded full chunks early ({@code ChunkMap.processUnloads}). Kept chunks are left out there
 * ({@code mixin.chunkload.ChunkMapKeptSaveMixin}). That save copies each section on the server thread and encodes it on the worker pool. A kept
 * chunk is saved when it is let go instead. With 4,096 kept, a chunk stays kept a few seconds.
 * Autosaves and {@code /save-all} still save kept chunks. A crash loses the kept chunks not saved yet, as it loses those under
 * Chunky's tickets. On shutdown vanilla removes all tickets first, so kept chunks are saved then.
 * Nothing is kept while the heap is nearly full ({@link MemoryPressure}), with {@code chunkyKeptChunks = 0} in
 * {@code scheduling.toml} or with {@code -Dgeneratoraccelerator2.chunkyHolds=false}. The mixin that drives this is not applied
 * while C2ME or Moonrise runs the chunk system.
 */
public final class ChunkyHolds {
    /** A dimension's chunk cache, which owns its holds. */
    public interface Owner {
        /** Null until Chunky's first ticket in the dimension. */
        @Nullable
        ChunkyHolds ga2$chunkyHolds();
    }

    /** What the holds do to the game's tickets. */
    public interface Tickets {
        /** Gives the chunk at {@code pos} GA2's ticket if the chunk is loaded. Returns whether it did. */
        boolean keep(long pos);

        /** Takes GA2's ticket from the chunk at {@code pos}. */
        void letGo(long pos);
    }

    private static final boolean SWITCH = !"false".equals(System.getProperty("generatoraccelerator2.chunkyHolds"));
    private static final boolean CHUNKY = SWITCH && Compat.isLoaded(ChunkyRequests.MOD_ID);
    /** Chunky's ticket types, read from its world class on the first radius-0 ticket. Empty without Chunky. */
    private static volatile Set<TicketType> chunkyTypes;

    /** At most this many kept chunks for an automatic count. Twice as many kept 10% more chunks. */
    static final int MAX_AUTO = 4096;
    /** One kept chunk per 1.5 MB of heap: 4,096 at 6 GB. A full chunk with its light takes a few hundred KB. */
    static final long HEAP_PER_CHUNK = 3L << 19;
    /** No Chunky ticket for this long: all kept chunks are let go. */
    static final long IDLE_NANOS = 10_000_000_000L;

    public static final LongAdder KEPT = new LongAdder();
    public static final LongAdder DONE = new LongAdder();
    public static final LongAdder CAPPED = new LongAdder();
    public static final LongAdder IDLE = new LongAdder();
    private static final AtomicInteger KEPT_NOW = new AtomicInteger();

    private final Tickets tickets;
    private final int radius;
    private final int area;
    private final Long2ObjectOpenHashMap<Tile> tiles = new Long2ObjectOpenHashMap<>();
    /** Chunky's tickets this class saw added and not yet removed. */
    private final LongOpenHashSet open = new LongOpenHashSet();
    /**
     * Kept chunks, each with the number of chunks within the radius Chunky has not asked for yet. The chunk a request came near
     * least recently is first. No count is 0, so a missing chunk reads as 0.
     */
    private final Long2IntLinkedOpenHashMap kept = new Long2IntLinkedOpenHashMap();
    /** The chunks one {@link #cover} call left without a Chunky ticket near them, in the loop's order. */
    private final LongArrayList freed = new LongArrayList();
    private int cap;
    private long lastSeen;

    /** A 32 by 32 chunk square. Index {@code (z & 31) << 5 | (x & 31)}. */
    private static final class Tile {
        /** Open Chunky tickets within the radius of each chunk. */
        final short[] cover = new short[1024];
        /** One bit per chunk Chunky asked for, in index order. */
        final long[] asked = new long[16];
        /**
         * One bit per kept chunk, in index order, the same set as {@link #kept}'s keys. A request's neighbours and {@link #isKept} read
         * these bits first, so the map is asked only about chunks that are kept.
         */
        final long[] kept = new long[16];
    }

    public ChunkyHolds(Tickets tickets, int radius, int cap) {
        this.tickets = tickets;
        this.radius = radius;
        int side = 2 * radius + 1;
        this.area = side * side;
        this.cap = cap;
    }

    /**
     * Whether {@code type} is Chunky's ticket. Chunky makes its ticket type without a name, so its own static field tells it
     * apart. Tickets compare types by identity, as here.
     */
    public static boolean isChunky(TicketType type) {
        Set<TicketType> known = chunkyTypes;
        if (known == null) {
            chunkyTypes = known = CHUNKY ? ChunkyRequests.ticketTypes() : Set.of();
            if (!known.isEmpty()) {
                Ga2Log.get("Compat").info("Chunky ticket type found -> chunks next to its requests kept loaded, at most {} per dimension", cap());
            }
        }
        return known.contains(type);
    }

    /** How many chunks a dimension keeps at most now: 0 while off or the heap is nearly full. */
    public static int cap() {
        if (!SWITCH || MemoryPressure.high()) {
            return 0;
        }
        int configured = SchedulingConfig.chunkyKeptChunks();
        return configured >= 0 ? configured : autoCap(Runtime.getRuntime().maxMemory());
    }

    static int autoCap(long maxHeap) {
        return (int) Math.min(MAX_AUTO, maxHeap / HEAP_PER_CHUNK);
    }

    /** Chunky added its ticket at {@code x, z}. */
    public void asked(int x, int z, long now) {
        if (this.cap <= 0 || !this.open.add(ChunkPos.pack(x, z))) {
            return;
        }
        this.lastSeen = now;
        this.cover(x, z, 1);
        Tile tile = this.tiles.get(ChunkPos.pack(x >> 5, z >> 5));
        int i = index(x, z);
        long bit = 1L << (i & 63);
        if ((tile.asked[i >>> 6] & bit) != 0) {
            return;
        }
        tile.asked[i >>> 6] |= bit;
        if (this.kept.isEmpty()) {
            return;
        }
        int r = this.radius;
        int x0 = x - r;
        int x1 = x + r;
        int z0 = z - r;
        int z1 = z + r;
        for (int tx = x0 >> 5; tx <= x1 >> 5; tx++) {
            int ax = Math.max(x0, tx << 5) & 31;
            int bx = Math.min(x1, (tx << 5) + 31) & 31;
            long mask = ((1L << (bx - ax + 1)) - 1) << ax;
            for (int tz = z0 >> 5; tz <= z1 >> 5; tz++) {
                Tile near = this.tiles.get(ChunkPos.pack(tx, tz));
                if (near == null) {
                    continue;
                }
                int az = Math.max(z0, tz << 5);
                int bz = Math.min(z1, (tz << 5) + 31);
                for (int qz = az; qz <= bz; qz++) {
                    int lz = qz & 31;
                    // a row is 32 bits: the low or high half of a word
                    long row = (near.kept[lz >>> 1] >>> ((lz & 1) << 5)) & mask;
                    while (row != 0) {
                        int lx = Long.numberOfTrailingZeros(row);
                        row &= row - 1;
                        this.cameNear((tx << 5) | lx, qz, near);
                    }
                }
            }
        }
    }

    /** A request came near the kept chunk {@code x, z} of {@code tile}: one chunk fewer to wait for, or let go at none. */
    private void cameNear(int x, int z, Tile tile) {
        long q = ChunkPos.pack(x, z);
        int left = this.kept.get(q);
        if (left == 1) {
            this.kept.remove(q);
            setKept(tile, x, z, false);
            KEPT_NOW.decrementAndGet();
            this.tickets.letGo(q);
            DONE.increment();
        } else {
            this.kept.putAndMoveToLast(q, left - 1);
        }
    }

    private static void setKept(Tile tile, int x, int z, boolean kept) {
        int i = index(x, z);
        long bit = 1L << (i & 63);
        tile.kept[i >>> 6] = kept ? tile.kept[i >>> 6] | bit : tile.kept[i >>> 6] & ~bit;
    }

    /** {@link Tile#kept}'s bit for the chunk at {@code x, z}, false without a tile. */
    private boolean keptBit(int x, int z) {
        Tile tile = this.tiles.get(ChunkPos.pack(x >> 5, z >> 5));
        if (tile == null) {
            return false;
        }
        int i = index(x, z);
        return (tile.kept[i >>> 6] & 1L << (i & 63)) != 0;
    }

    /** Chunky removed its ticket at {@code x, z}. */
    public void done(int x, int z, long now) {
        if (!this.open.remove(ChunkPos.pack(x, z))) {
            return;
        }
        this.lastSeen = now;
        this.cover(x, z, -1);
    }

    /** Once per server tick. {@code cap} is {@link #cap()}. */
    public void tick(long now, int cap) {
        this.cap = cap;
        this.shrink(cap);
        if (this.open.isEmpty() && !this.tiles.isEmpty() && now - this.lastSeen > IDLE_NANOS) {
            IDLE.add(this.kept.size());
            while (!this.kept.isEmpty()) {
                this.letGoFirst();
            }
            this.tiles.clear();
            this.tiles.trim();
        }
    }

    int keptCount() {
        return this.kept.size();
    }

    /** Whether GA2's ticket keeps the chunk at {@code pos}. */
    public boolean isKept(long pos) {
        return this.keptBit(ChunkPos.getX(pos), ChunkPos.getZ(pos));
    }

    /**
     * Adds {@code delta} to the cover of each chunk within the radius of {@code x, z}. The chunks it leaves uncovered are handled
     * after the loop. The loop then stays small for the JIT, and {@link #uncovered} does not change the covers.
     */
    private void cover(int x, int z, int delta) {
        int r = this.radius;
        int x0 = x - r;
        int x1 = x + r;
        int z0 = z - r;
        int z1 = z + r;
        for (int tx = x0 >> 5; tx <= x1 >> 5; tx++) {
            int ax = Math.max(x0, tx << 5);
            int bx = Math.min(x1, (tx << 5) + 31);
            for (int tz = z0 >> 5; tz <= z1 >> 5; tz++) {
                long key = ChunkPos.pack(tx, tz);
                Tile tile = this.tiles.get(key);
                if (tile == null) {
                    tile = new Tile();
                    this.tiles.put(key, tile);
                }
                int az = Math.max(z0, tz << 5);
                int bz = Math.min(z1, (tz << 5) + 31);
                for (int qz = az; qz <= bz; qz++) {
                    int row = (qz & 31) << 5;
                    for (int qx = ax; qx <= bx; qx++) {
                        int c = tile.cover[row | (qx & 31)] += (short) delta;
                        if (c == 0) {
                            this.freed.add(ChunkPos.pack(qx, qz));
                        }
                    }
                }
            }
        }
        if (!this.freed.isEmpty()) {
            this.uncoveredAll();
        }
    }

    private void uncoveredAll() {
        for (int i = 0, n = this.freed.size(); i < n; i++) {
            long q = this.freed.getLong(i);
            this.uncovered(ChunkPos.getX(q), ChunkPos.getZ(q));
        }
        this.freed.clear();
    }

    /** No Chunky ticket within the radius of {@code x, z} is left. */
    private void uncovered(int x, int z) {
        long q = ChunkPos.pack(x, z);
        if (this.keptBit(x, z)) {
            this.kept.getAndMoveToLast(q);
            return;
        }
        if (this.cap <= 0) {
            return;
        }
        int left = this.area - this.askedAround(x, z);
        if (left <= 0 || !this.tickets.keep(q)) {
            return;
        }
        this.kept.put(q, left);
        // cover made the tile when it counted this chunk's tickets
        setKept(this.tiles.get(ChunkPos.pack(x >> 5, z >> 5)), x, z, true);
        KEPT_NOW.incrementAndGet();
        KEPT.increment();
        this.shrink(this.cap);
    }

    /** The chunks within the radius of {@code x, z} Chunky has asked for. */
    private int askedAround(int x, int z) {
        int r = this.radius;
        int x0 = x - r;
        int x1 = x + r;
        int z0 = z - r;
        int z1 = z + r;
        int n = 0;
        for (int tx = x0 >> 5; tx <= x1 >> 5; tx++) {
            int ax = Math.max(x0, tx << 5) & 31;
            int bx = Math.min(x1, (tx << 5) + 31) & 31;
            long mask = ((1L << (bx - ax + 1)) - 1) << ax;
            for (int tz = z0 >> 5; tz <= z1 >> 5; tz++) {
                Tile tile = this.tiles.get(ChunkPos.pack(tx, tz));
                if (tile == null) {
                    continue;
                }
                int az = Math.max(z0, tz << 5);
                int bz = Math.min(z1, (tz << 5) + 31);
                for (int qz = az; qz <= bz; qz++) {
                    int lz = qz & 31;
                    // a row is 32 bits: the low or high half of a word
                    n += Long.bitCount((tile.asked[lz >>> 1] >>> ((lz & 1) << 5)) & mask);
                }
            }
        }
        return n;
    }

    /** Lets go of the chunks a request came near least recently until at most {@code cap} are kept. */
    private void shrink(int cap) {
        while (this.kept.size() > Math.max(0, cap)) {
            this.letGoFirst();
            CAPPED.increment();
        }
    }

    private void letGoFirst() {
        long first = this.kept.firstLongKey();
        this.kept.removeFirstInt();
        int fx = ChunkPos.getX(first);
        int fz = ChunkPos.getZ(first);
        setKept(this.tiles.get(ChunkPos.pack(fx >> 5, fz >> 5)), fx, fz, false);
        KEPT_NOW.decrementAndGet();
        this.tickets.letGo(first);
    }

    private static int index(int x, int z) {
        return (z & 31) << 5 | (x & 31);
    }

    /** The {@code /ga2 chunks} line, or null if nothing was kept. */
    public static String report() {
        long kept = KEPT.sum();
        if (kept == 0 && KEPT_NOW.get() == 0) {
            return null;
        }
        return String.format(Locale.ROOT, "chunks next to Chunky's requests kept loaded: %,d now (at most %,d per dimension); kept %,d, "
            + "let go once Chunky asked for all around them %,d, over the cap %,d, after Chunky stopped %,d",
            KEPT_NOW.get(), cap(), kept, DONE.sum(), CAPPED.sum(), IDLE.sum());
    }

    public static void resetCounts() {
        KEPT.reset();
        DONE.reset();
        CAPPED.reset();
        IDLE.reset();
    }

    /** The stopped server's dimensions took their kept chunks with them. */
    public static void serverStopped() {
        KEPT_NOW.set(0);
    }
}
