package dev.denismasterherobrine.generatoraccelerator2.compat.chunky;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

/**
 * The chunks {@link ChunkyHolds} keeps, against a model of the game's tickets: a chunk stays loaded while a Chunky ticket within
 * the radius or GA2's ticket is on it. Requests come in Chunky's region pattern (regions in a spiral, a Hilbert curve inside each
 * full region, columns in the others) with a fixed number open, the oldest finishing first.
 */
class ChunkyHoldsTest {
    private static final int R = 11;
    private static final long GB = 1L << 30;

    @Test
    void mixinsApply() {
        assertTrue(dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload.ChunkMapUpdatingInvoker.class
            .isAssignableFrom(net.minecraft.server.level.ChunkMap.class), "the chunk map hands out updating holders");
        boolean field = false;
        for (java.lang.reflect.Field f : net.minecraft.server.level.ServerChunkCache.class.getDeclaredFields()) {
            field |= f.getType() == ChunkyHolds.class;
        }
        assertTrue(field, "the server chunk cache tracks Chunky's tickets");
        assertTrue(ChunkyHolds.Owner.class.isAssignableFrom(net.minecraft.server.level.ServerChunkCache.class), "the cache hands out its holds");
        boolean skip = false;
        for (java.lang.reflect.Method m : net.minecraft.server.level.ChunkMap.class.getDeclaredMethods()) {
            skip |= m.getName().contains("notWhileKept");
        }
        assertTrue(skip, "the early saves leave kept chunks out");
    }

    @Test
    void keptChunksAreKnown() {
        World world = new World(65536);
        world.ask(0, 0);
        world.holds.asked(0, 0, world.now);
        world.finishOne(0, 0);
        world.holds.done(0, 0, world.now);
        world.settle();
        assertTrue(world.holds.isKept(ChunkPos.asLong(3, -4)), "a chunk next to the request is kept");
        assertTrue(!world.holds.isKept(ChunkPos.asLong(40, 0)), "a chunk far from it is not");
        world.finish();
        assertTrue(!world.holds.isKept(ChunkPos.asLong(3, -4)), "let go after Chunky stopped");
    }

    @Test
    void automaticCapFollowsTheHeap() {
        assertEquals(4096, ChunkyHolds.autoCap(6 * GB));
        assertEquals(4096, ChunkyHolds.autoCap(32 * GB));
        assertEquals(1365, ChunkyHolds.autoCap(2 * GB));
        assertEquals(341, ChunkyHolds.autoCap(GB / 2));
    }

    @Test
    void nothingIsReadBackWhenTheCapIsLargeEnough() {
        World world = new World(65536);
        world.run(order(-40, 17, 48), 64);
        assertEquals(0, world.readBack, "chunks read back after they were dropped");
        world.finish();
    }

    @Test
    void aSmallCapIsKeptAndStillHelps() {
        List<long[]> order = order(-375, 0, 48);
        World vanilla = new World(0);
        vanilla.run(order, 64);
        World small = new World(1024);
        small.run(order, 64);
        World auto = new World(4096);
        auto.run(order, 64);
        System.out.printf("chunks read back: %d without, %d with 1024 kept, %d with 4096 kept, of %d chunks%n", vanilla.readBack, small.readBack,
            auto.readBack, order.size());
        assertTrue(small.maxKept <= 1024, "kept " + small.maxKept + " of at most 1024");
        assertTrue(vanilla.readBack > 0, "vanilla reads chunks back");
        assertTrue(small.readBack < vanilla.readBack, "read back " + small.readBack + " with 1024 kept, " + vanilla.readBack + " without");
        assertTrue(auto.readBack * 2 < vanilla.readBack, "read back " + auto.readBack + " with 4096 kept, " + vanilla.readBack + " without");
        vanilla.finish();
        small.finish();
        auto.finish();
    }

    @Test
    void repeatedAndUnknownTicketsAreIgnored() {
        World world = new World(65536);
        world.holds.done(5, 5, world.now);
        assertEquals(0, world.tickets.kept.size(), "a removal without an add keeps nothing");
        world.ask(0, 0);
        world.holds.asked(0, 0, world.now);
        world.holds.asked(0, 0, world.now);
        world.settle();
        world.finishOne(0, 0);
        world.holds.done(0, 0, world.now);
        world.holds.done(0, 0, world.now);
        world.settle();
        int side = 2 * R + 1;
        assertEquals(side * side, world.tickets.kept.size(), "every chunk around the one request is still needed");
        world.finish();
    }

    @Test
    void nothingIsKeptWhileOff() {
        World world = new World(0);
        world.run(order(0, 0, 20), 32);
        assertEquals(0, world.maxKept);
        world.finish();
    }

    /** The game's side: Chunky's tickets, GA2's tickets and which chunks are loaded. */
    private static final class World {
        final FakeTickets tickets = new FakeTickets(this);
        final ChunkyHolds holds;
        final int cap;
        final Long2IntOpenHashMap cover = new Long2IntOpenHashMap();
        final LongOpenHashSet loaded = new LongOpenHashSet();
        final LongOpenHashSet dropped = new LongOpenHashSet();
        long now = 1;
        int readBack;
        int maxKept;

        World(int cap) {
            this.cap = cap;
            this.holds = new ChunkyHolds(this.tickets, R, cap);
        }

        void run(List<long[]> order, int window) {
            ArrayDeque<long[]> open = new ArrayDeque<>();
            for (long[] p : order) {
                if (open.size() == window) {
                    long[] f = open.poll();
                    this.finishOne((int) f[0], (int) f[1]);
                    this.holds.done((int) f[0], (int) f[1], this.now);
                    this.settle();
                }
                this.ask((int) p[0], (int) p[1]);
                this.holds.asked((int) p[0], (int) p[1], this.now);
                this.settle();
                open.add(p);
                if (++this.now % 50 == 0) {
                    this.holds.tick(this.now, this.cap);
                    this.settle();
                }
            }
            while (!open.isEmpty()) {
                long[] f = open.poll();
                this.finishOne((int) f[0], (int) f[1]);
                this.holds.done((int) f[0], (int) f[1], this.now);
                this.settle();
            }
        }

        /** Chunky's ticket at x, z: the chunks around it load, from disk if they were dropped before. */
        void ask(int x, int z) {
            for (int dx = -R; dx <= R; dx++) {
                for (int dz = -R; dz <= R; dz++) {
                    long q = ChunkPos.asLong(x + dx, z + dz);
                    this.cover.addTo(q, 1);
                    if (this.dropped.remove(q)) {
                        this.readBack++;
                    }
                    this.loaded.add(q);
                }
            }
        }

        final LongArrayList uncovered = new LongArrayList();

        void finishOne(int x, int z) {
            for (int dx = -R; dx <= R; dx++) {
                for (int dz = -R; dz <= R; dz++) {
                    long q = ChunkPos.asLong(x + dx, z + dz);
                    if (this.cover.addTo(q, -1) == 1) {
                        this.cover.remove(q);
                        this.uncovered.add(q);
                    }
                }
            }
        }

        /** The next ticket update: chunks without any ticket unload. */
        void settle() {
            for (int i = 0; i < this.uncovered.size(); i++) {
                long q = this.uncovered.getLong(i);
                if (!this.tickets.kept.contains(q) && this.cover.get(q) == 0) {
                    this.drop(q);
                }
            }
            this.uncovered.clear();
            this.maxKept = Math.max(this.maxKept, this.tickets.kept.size());
            assertEquals(this.tickets.kept.size(), this.holds.keptCount(), "GA2's tickets against the chunks it keeps");
            assertTrue(this.cap <= 0 || this.tickets.kept.size() <= this.cap, "over the cap: " + this.tickets.kept.size());
        }

        void drop(long q) {
            if (this.loaded.remove(q)) {
                this.dropped.add(q);
            }
        }

        /** Chunky stopped: after the idle time every kept chunk is let go. */
        void finish() {
            this.settle();
            this.holds.tick(this.now + ChunkyHolds.IDLE_NANOS + 1, this.cap);
            this.settle();
            assertEquals(0, this.tickets.kept.size(), "kept after Chunky stopped");
            assertEquals(0, this.loaded.size(), "loaded after Chunky stopped");
        }
    }

    private static final class FakeTickets implements ChunkyHolds.Tickets {
        final World world;
        final LongOpenHashSet kept = new LongOpenHashSet();

        FakeTickets(World world) {
            this.world = world;
        }

        @Override
        public boolean keep(long pos) {
            if (!this.world.loaded.contains(pos)) {
                return false;
            }
            assertTrue(this.kept.add(pos), "kept twice: " + new ChunkPos(pos));
            return true;
        }

        @Override
        public void letGo(long pos) {
            assertTrue(this.kept.remove(pos), "let go without a ticket: " + new ChunkPos(pos));
            if (this.world.cover.get(pos) == 0) {
                this.world.drop(pos);
            }
        }
    }

    /** Chunky's region pattern over the square of radius {@code r} around {@code cx, cz}. */
    private static List<long[]> order(int cx, int cz, int r) {
        int[][] hilbert = hilbert32();
        int crx = cx >> 5;
        int crz = cz >> 5;
        int radReg = Math.max(Math.max(((cx + r) >> 5) - crx, crx - ((cx - r) >> 5)), Math.max(((cz + r) >> 5) - crz, crz - ((cz - r) >> 5)));
        List<int[]> regions = new ArrayList<>();
        int rx = crx;
        int rz = crz;
        int ring = 0;
        int span = 0;
        int down = 0;
        int left = 0;
        int up = 0;
        int right = 0;
        boolean more = true;
        while (more) {
            regions.add(new int[] {rx, rz});
            if (rx == crx + ring && rz == crz + ring) {
                ring++;
                rx++;
                rz++;
                more = ring <= radReg;
                span = 2 * ring;
                down = left = up = right = 0;
            }
            if (down < span) {
                rz--;
                down++;
            } else if (left < span) {
                rx--;
                left++;
            } else if (up < span) {
                rz++;
                up++;
            } else if (right < span) {
                rx++;
                right++;
            }
        }
        List<long[]> out = new ArrayList<>();
        for (int[] region : regions) {
            int bx = region[0] << 5;
            int bz = region[1] << 5;
            int ax = Math.max(bx, cx - r);
            int az = Math.max(bz, cz - r);
            int ex = Math.min(bx + 31, cx + r);
            int ez = Math.min(bz + 31, cz + r);
            if (ax > ex || az > ez) {
                continue;
            }
            if ((ex - ax + 1) * (ez - az + 1) == 1024) {
                for (int[] o : hilbert) {
                    out.add(new long[] {bx + o[0], bz + o[1]});
                }
            } else {
                for (int x = ax; x <= ex; x++) {
                    for (int z = az; z <= ez; z++) {
                        out.add(new long[] {x, z});
                    }
                }
            }
        }
        return out;
    }

    /** The 32 by 32 Hilbert curve, as offsets in visiting order. */
    private static int[][] hilbert32() {
        int n = 32;
        int[][] out = new int[n * n][];
        for (int d = 0; d < n * n; d++) {
            int x = 0;
            int z = 0;
            int t = d;
            for (int s = 1; s < n; s *= 2) {
                int a = 1 & (t / 2);
                int b = 1 & (t ^ a);
                if (b == 0) {
                    if (a == 1) {
                        x = s - 1 - x;
                        z = s - 1 - z;
                    }
                    int swap = x;
                    x = z;
                    z = swap;
                }
                x += s * a;
                z += s * b;
                t /= 4;
            }
            out[d] = new int[] {x, z};
        }
        return out;
    }
}
