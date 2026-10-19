package dev.denismasterherobrine.generatoraccelerator2.scheduling;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * Chunk-area locks for the chunk steps that run on the worker pool but touch more than their own chunk.
 *
 * <p>A step holds the chunks it reads and the chunks it writes, per dimension, as a readers-writer lock per chunk. No
 * two steps write the same chunk, and no step reads a chunk that another step writes. A FEATURES step writes its 3x3
 * and reads the square of {@link SchedulingConfig#featuresGuardRadius()} around its chunk. That square is the ring
 * around the step's writes that big features read. With the default radius 2, two decorations run side by side only
 * when neither writes into the 5x5 the other reads. They are then at least 4 chunks apart, so neither ever sees the
 * other half done. Until Oct 2026 the 5x5 squares themselves were exclusive. That kept decorations 5 chunks apart for
 * the same guarantee: at most one chunk in 25 decorating at a time instead of one in 16. INITIALIZE_LIGHT reads its
 * own chunk. It scans the chunk's blocks for sky light sources while a neighbour's decoration may write into the
 * chunk. Vanilla runs both steps on one thread.
 *
 * <p>Grants are all or nothing and never block. A request whose keys are not all free is parked. The release that
 * frees the keys grants the request on the releasing thread, which only dispatches the work. A holder never waits for
 * another lock, and every request is registered from the single worldgen mailbox thread, so there is no lock cycle.
 * Parked requests are retried on every release, the most urgent chunk first: by the chunk holder's queue level, then
 * by arrival. They are granted greedily. A later request may run before an earlier one it does not conflict with
 * right now. Each chunk decorates once, so nothing waits forever. Parked requests are indexed by the keys they need.
 * So a release retries only the requests that share a key with the released request. No other parked request can
 * have become grantable by that release. Running two conflicting steps one after the other, in either order, is
 * something vanilla's own scheduling can produce, so the result is a vanilla result.
 */
public final class AreaLocks {
    private static final Map<ServerLevel, AreaLocks> BY_LEVEL = Collections.synchronizedMap(new WeakHashMap<>());

    /** Chunks a holder writes (one writer each) and how many holders read each chunk (a writer reads what it writes too). */
    private final LongOpenHashSet writeHeld = new LongOpenHashSet();
    private final Long2IntOpenHashMap readHeld = new Long2IntOpenHashMap();
    /**
     * Parked requests by each chunk they read and each chunk they write. Releasing a writer of a chunk they read may let them
     * run. Releasing a reader of a chunk they write may too.
     */
    private final Long2ObjectOpenHashMap<ArrayList<Request>> waitingRead = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<ArrayList<Request>> waitingWrite = new Long2ObjectOpenHashMap<>();
    private int parkedCount;
    private long arrivals;
    /** Marks the candidates of one release (each is retried once). */
    private int visitStamp;
    private int holders;
    /** Occupancy over time in count-seconds (monitor): decorations and light initializations holding locks, requests parked. */
    private long statAt = System.nanoTime();
    private int decorations, initializations;
    private double decorationTime, initializationTime, parkedTime;

    private void account() {
        long now = System.nanoTime();
        double dt = (now - statAt) / 1e9;
        statAt = now;
        decorationTime += decorations * dt;
        initializationTime += initializations * dt;
        parkedTime += parkedCount * dt;
    }

    /**
     * Count-seconds so far over every level: decorations running under their locks, light initializations running, requests
     * parked. A bench divides the change over a pass by the pass's length to get the average number of each.
     */
    public static double[] occupancy() {
        double[] sum = new double[3];
        synchronized (BY_LEVEL) {
            for (AreaLocks locks : BY_LEVEL.values()) {
                synchronized (locks) {
                    locks.account();
                    sum[0] += locks.decorationTime;
                    sum[1] += locks.initializationTime;
                    sum[2] += locks.parkedTime;
                }
            }
        }
        return sum;
    }

    public static AreaLocks of(ServerLevel level) {
        return BY_LEVEL.computeIfAbsent(level, l -> new AreaLocks());
    }

    /** The chunks a step reads and writes (every written chunk is among the read ones) and what to do once a parked request is granted. */
    public static final class Request {
        final long[] read;
        final long[] write;
        /** A decoration (else a light initialization), for the occupancy counts. */
        final boolean decoration;
        /** The chunk holder's queue level when the request was made (lower runs first). */
        final int level;
        Runnable onLateGrant;
        /** False while the step waits for something before it may run (a GPU result). Parked, passed over, until {@link AreaLocks#ready}. */
        volatile boolean ready = true;
        /** Arrival order (monitor), whether parked now, and the last release that retried it. */
        long seq;
        boolean parked;
        int visit;

        /** Holds the request back until {@link AreaLocks#ready} (call before {@link AreaLocks#acquireOrPark}). */
        public Request hold() {
            ready = false;
            return this;
        }

        Request(long[] read, long[] write, boolean decoration, int level) {
            this.read = read;
            this.write = write;
            this.decoration = decoration;
            this.level = level;
        }
    }

    /** FEATURES of {@code pos}: reads the square within {@code guardRadius} (at least the 3x3), writes the 3x3. */
    public static Request features(ChunkPos pos, int guardRadius, int level) {
        return new Request(square(pos, Math.max(1, guardRadius)), square(pos, 1), true, level);
    }

    /** INITIALIZE_LIGHT of {@code pos}: reads the chunk itself. */
    public static Request single(ChunkPos pos, int level) {
        return new Request(new long[] { ChunkPos.asLong(pos.x, pos.z) }, new long[0], false, level);
    }

    private static long[] square(ChunkPos pos, int radius) {
        int side = 2 * radius + 1;
        long[] keys = new long[side * side];
        int i = 0;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                keys[i++] = ChunkPos.asLong(pos.x + dx, pos.z + dz);
            }
        }
        return keys;
    }

    /** True when nothing holds or waits for a lock (every request is registered by the mailbox thread). */
    public synchronized boolean idle() {
        return holders == 0 && parkedCount == 0;
    }

    /**
     * Takes every key of {@code request} now and returns true, or parks the request and returns false. A
     * parked request's {@code onLateGrant} runs once a release has granted it.
     */
    public boolean acquireOrPark(Request request, Runnable onLateGrant) {
        request.onLateGrant = onLateGrant;
        synchronized (this) {
            request.seq = arrivals++;
            if (take(request)) {
                return true;
            }
            park(request);
            return false;
        }
    }

    /** Indexes a request under every key it needs (caller holds the monitor). */
    private void park(Request request) {
        account();
        request.parked = true;
        parkedCount++;
        for (long key : request.read) {
            waitingRead.computeIfAbsent(key, k -> new ArrayList<>(4)).add(request);
        }
        for (long key : request.write) {
            waitingWrite.computeIfAbsent(key, k -> new ArrayList<>(4)).add(request);
        }
    }

    /** Takes a granted request out of the index (caller holds the monitor). */
    private void unpark(Request request) {
        account();
        request.parked = false;
        parkedCount--;
        unindex(waitingRead, request.read, request);
        unindex(waitingWrite, request.write, request);
    }

    private static void unindex(Long2ObjectOpenHashMap<ArrayList<Request>> index, long[] keys, Request request) {
        for (long key : keys) {
            ArrayList<Request> list = index.get(key);
            if (list != null) {
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i) == request) {
                        list.remove(i);
                        break;
                    }
                }
                if (list.isEmpty()) {
                    index.remove(key);
                }
            }
        }
    }

    /**
     * What a held request ({@link Request#hold}) waited for is available. The request is granted now when its keys are free, and its
     * step is then dispatched on this thread. Otherwise a later release grants it.
     */
    public void ready(Request request) {
        boolean granted = false;
        synchronized (this) {
            request.ready = true;
            if (request.parked && take(request)) {
                unpark(request);
                granted = true;
            }
        }
        if (granted) {
            request.onLateGrant.run();
        }
    }

    /** Releases {@code request}'s keys and dispatches every parked request that can now run. */
    public void release(Request request) {
        List<Request> granted = null;
        synchronized (this) {
            for (long key : request.read) {
                if (readHeld.addTo(key, -1) == 1) {
                    readHeld.remove(key);
                }
            }
            for (long key : request.write) {
                writeHeld.remove(key);
            }
            account();
            holders--;
            if (request.decoration) {
                decorations--;
            } else {
                initializations--;
            }
            if (parkedCount > 0) {
                // only requests that read a chunk this request wrote, or write a chunk it read, can have become grantable. They are
                // retried in order of urgency, then arrival
                int stamp = ++visitStamp;
                ArrayList<Request> candidates = null;
                for (int s = 0; s < 2; s++) {
                    long[] keys = s == 0 ? request.write : request.read;
                    Long2ObjectOpenHashMap<ArrayList<Request>> index = s == 0 ? waitingRead : waitingWrite;
                    for (long key : keys) {
                        ArrayList<Request> list = index.get(key);
                        if (list != null) {
                            for (Request p : list) {
                                if (p.visit != stamp) {
                                    p.visit = stamp;
                                    if (candidates == null) {
                                        candidates = new ArrayList<>();
                                    }
                                    candidates.add(p);
                                }
                            }
                        }
                    }
                }
                if (candidates != null) {
                    candidates.sort((a, b) -> a.level != b.level ? Integer.compare(a.level, b.level) : Long.compare(a.seq, b.seq));
                    for (Request p : candidates) {
                        if (p.parked && take(p)) {
                            unpark(p);
                            if (granted == null) {
                                granted = new ArrayList<>();
                            }
                            granted.add(p);
                        }
                    }
                }
            }
        }
        if (granted != null) {
            for (Request p : granted) {
                p.onLateGrant.run();
            }
        }
    }

    /** Takes the request's chunks if no holder writes a chunk it reads and none reads a chunk it writes (caller holds the monitor). */
    private boolean take(Request request) {
        if (!request.ready) {
            return false;
        }
        for (long key : request.read) {
            if (writeHeld.contains(key)) {
                return false;
            }
        }
        for (long key : request.write) {
            if (readHeld.containsKey(key)) {
                return false;
            }
        }
        for (long key : request.read) {
            readHeld.addTo(key, 1);
        }
        for (long key : request.write) {
            writeHeld.add(key);
        }
        account();
        holders++;
        if (request.decoration) {
            decorations++;
        } else {
            initializations++;
        }
        return true;
    }

    /** Requests holding locks and parked, for {@code /ga2 steps stats}. */
    public synchronized int[] counts() {
        return new int[] { holders, parkedCount };
    }

    public static int[] totals() {
        int[] sum = new int[2];
        synchronized (BY_LEVEL) {
            for (AreaLocks locks : BY_LEVEL.values()) {
                int[] c = locks.counts();
                sum[0] += c[0];
                sum[1] += c[1];
            }
        }
        return sum;
    }
}
