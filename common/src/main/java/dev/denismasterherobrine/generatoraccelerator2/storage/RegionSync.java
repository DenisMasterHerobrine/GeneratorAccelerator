package dev.denismasterherobrine.generatoraccelerator2.storage;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import javax.annotation.Nullable;

/**
 * Region files synced in batches instead of on every chunk write.
 *
 * <p>A singleplayer world, and a dedicated server with {@code sync-chunk-writes=true} (the default), opens its region files
 * with {@code DSYNC}: every write waits until the disk has the data. A chunk save is two such writes, its sectors and then
 * the 8 KB header at the start of the file, so on a hard disk a save costs two head moves and two flushes, 10 to 30 ms, on
 * the one IO thread each storage has. That holds a hard disk to about 50 saved chunks a second whatever the processors do,
 * and the worldgen thread waits for that thread near every new region.
 *
 * <p>Here the files are opened without {@code DSYNC}. The IO thread writes the same bytes to the same places, and the file
 * is forced to the disk once per batch: when {@link #MAX_BATCH} chunks were written since the last force, or
 * {@link #delayMs} after the first of them (a thread of its own forces files whose time is up). Vanilla's own flush points
 * stay: {@code RegionFile.flush} (a {@code /save-all flush}, the shutdown) and {@code close} force the file as before. So
 * what reaches the disk is the same, and a crash loses at most the last batch, the way vanilla's {@code sync-chunk-writes=false}
 * loses the last writes. {@code syncDelayMs = 0} in the storage config keeps vanilla's write-through, as does any other mod's
 * change to the region file's writes ({@link HookTargets#STORAGE_REGION_SYNC}).
 */
public final class RegionSync {
    private RegionSync() {}

    private static final Logger LOGGER = Ga2Log.get("Storage");
    /** Writes to a file before it is forced ahead of its time. */
    static final int MAX_BATCH = 64;
    public static final LongAdder WRITES = new LongAdder();
    public static final LongAdder FORCES = new LongAdder();
    /** Forces because a batch was full, because its time was up, and by vanilla's own flush. */
    public static final LongAdder FORCES_FULL = new LongAdder();
    public static final LongAdder FORCES_DUE = new LongAdder();
    public static final LongAdder FLUSHES = new LongAdder();

    /** What the region file mixin gives this class. */
    public interface Batched {
        boolean ga2$batched();

        void ga2$setBatched(boolean batched);

        /** {@code System.nanoTime()} of the first write since the last force, or 0 when clean. */
        long ga2$dirtySince();

        void ga2$setDirtySince(long nanos);

        int ga2$dirtyWrites();

        void ga2$setDirtyWrites(int writes);

        boolean ga2$closed();

        FileChannel ga2$channel();
    }

    /** Set between {@code RegionFileStorage}'s decision and the constructor it then runs on the same thread. */
    private static final ThreadLocal<boolean[]> CLAIM = ThreadLocal.withInitial(() -> new boolean[1]);
    private static final Set<Batched> DIRTY = ConcurrentHashMap.newKeySet();
    private static final Object TIMER_LOCK = new Object();
    private static Thread timer;

    /** The sync delay, or 0 for vanilla's write-through. {@code -Dgeneratoraccelerator2.syncDelayMs} overrides the config. */
    public static int delayMs() {
        Integer property = Integer.getInteger("generatoraccelerator2.syncDelayMs");
        if (property != null) {
            return Math.max(0, property);
        }
        return StorageConfig.syncDelayMs();
    }

    public static boolean enabled() {
        return delayMs() > 0 && HookTargets.allowed(HookTargets.STORAGE_REGION_SYNC);
    }

    /**
     * Whether a synced write costs enough here for batching to pay, decided once per run by {@link #probe}. Null until probed.
     * Measured on a Windows desktop: a write-through write of a chunk and its header costs 0.3 to 0.5 ms more than a plain
     * write on a hard disk (the disk's own cache takes the write) and 0.16 ms on an NVMe drive, while a full flush costs 35 to
     * 40 ms on the hard disks and 2 ms on the NVMe drive. Batching loses there. It pays where the system waits for the platter
     * on every write-through write, 10 to 30 ms each.
     */
    private static volatile Boolean worthIt;
    /** Write-through cost, in ms per 8 KB write, from which batching pays. 0 skips the probe and batches everywhere. {@code -Dgeneratoraccelerator2.syncProbeMs}. */
    static final double PROBE_THRESHOLD_MS = Double.parseDouble(System.getProperty("generatoraccelerator2.syncProbeMs", "2.0"));
    private static volatile double probedMs = Double.NaN;

    /**
     * The {@code sync} flag a region file is opened with. A file that would be synced on every write is opened without
     * {@code DSYNC} and claimed for batching, when a probe in {@code folder} finds write-through writes slow: the constructor
     * that follows on this thread takes the claim ({@link #consumeClaim}). Without a folder nothing is probed and batching is on.
     */
    public static boolean openUnsynced(boolean sync, @Nullable Path folder) {
        if (!sync || !enabled()) {
            return sync;
        }
        if (folder != null) {
            Boolean worth = worthIt;
            if (worth == null) {
                synchronized (TIMER_LOCK) {
                    worth = worthIt;
                    if (worth == null) {
                        worth = probe(folder);
                        worthIt = worth;
                    }
                }
            }
            if (!worth) {
                return true;
            }
        }
        CLAIM.get()[0] = true;
        return false;
    }

    /** Times a few write-through writes of a region sector's size in {@code folder}, on the storage's own disk. */
    private static boolean probe(Path folder) {
        if (PROBE_THRESHOLD_MS <= 0.0) {
            return true;
        }
        Path file = folder.resolve(".ga2-sync-probe");
        try {
            Files.createDirectories(folder);
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            for (int i = 0; i < buffer.capacity(); i++) {
                buffer.put(i, (byte) (i * 31));
            }
            double ms;
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.DSYNC)) {
                buffer.position(0);
                channel.write(buffer, 0L); // the file's first write, which also makes it
                int writes = 4;
                long start = System.nanoTime();
                for (int i = 1; i <= writes; i++) {
                    buffer.position(0);
                    channel.write(buffer, (long) i * 8192L);
                }
                ms = (System.nanoTime() - start) / 1e6 / writes;
            } finally {
                Files.deleteIfExists(file);
            }
            probedMs = ms;
            boolean worth = ms >= PROBE_THRESHOLD_MS;
            LOGGER.info("region write-through costs {} ms per write -> {}", String.format(Locale.ROOT, "%.2f", ms),
                worth ? "files synced in batches" : "vanilla's write-through kept");
            return worth;
        } catch (IOException e) {
            LOGGER.info("region write-through probe failed ({}) -> vanilla's write-through kept", e.toString());
            return false;
        }
    }

    /** True once per {@link #openUnsynced} that returned false, for the region file constructed right after it. */
    public static boolean consumeClaim() {
        boolean[] claim = CLAIM.get();
        boolean claimed = claim[0];
        claim[0] = false;
        return claimed;
    }

    /** A chunk or header write of {@code file} ended. */
    public static void written(Batched file) {
        if (!file.ga2$batched()) {
            return;
        }
        WRITES.increment();
        int writes = file.ga2$dirtyWrites() + 1;
        file.ga2$setDirtyWrites(writes);
        if (file.ga2$dirtySince() == 0L) {
            file.ga2$setDirtySince(System.nanoTime());
            DIRTY.add(file);
            ensureTimer();
        }
        if (writes >= MAX_BATCH) {
            force(file, FORCES_FULL);
        }
    }

    /** Vanilla forced the file itself ({@code flush}). */
    public static void flushed(Batched file) {
        if (file.ga2$batched()) {
            clean(file);
            FLUSHES.increment();
        }
    }

    /** The file is being closed, which forces it. */
    public static void closing(Batched file) {
        if (file.ga2$batched()) {
            clean(file);
        }
    }

    private static void clean(Batched file) {
        file.ga2$setDirtySince(0L);
        file.ga2$setDirtyWrites(0);
        DIRTY.remove(file);
    }

    /**
     * Forces the file. It is marked clean first: a write that lands meanwhile marks it dirty again and is forced with the next
     * batch, whether or not this force caught it. {@code FileChannel.force} may run while the IO thread writes to the channel.
     */
    static void force(Batched file, LongAdder reason) {
        clean(file);
        if (file.ga2$closed()) {
            return;
        }
        try {
            file.ga2$channel().force(true);
            FORCES.increment();
            reason.increment();
        } catch (ClosedChannelException e) {
            // closed meanwhile. close forces the file itself
        } catch (IOException e) {
            LOGGER.warn("region file force failed -> {}", e.toString());
        }
    }

    /** Forces every file whose first unforced write is at least {@code delayNanos} old. For the timer and the tests. */
    static int forceDue(long now, long delayNanos) {
        int forced = 0;
        for (Batched file : DIRTY) {
            long since = file.ga2$dirtySince();
            if (since != 0L && now - since >= delayNanos) {
                force(file, FORCES_DUE);
                forced++;
            }
        }
        return forced;
    }

    /** Files waiting for a force. */
    public static int dirty() {
        return DIRTY.size();
    }

    private static void ensureTimer() {
        if (timer != null) {
            return;
        }
        synchronized (TIMER_LOCK) {
            if (timer == null) {
                Thread t = new Thread(RegionSync::run, "GA2 Region Sync");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY);
                timer = t;
                t.start();
            }
        }
    }

    /** Forces files whose time is up, every half delay. Ends after 30 s without a dirty file. */
    private static void run() {
        long idleSince = 0L;
        while (true) {
            int delay = Math.max(1, delayMs());
            LockSupport.parkNanos((long) Math.max(5, delay / 2) * 1_000_000L);
            long now = System.nanoTime();
            if (DIRTY.isEmpty()) {
                if (idleSince == 0L) {
                    idleSince = now;
                } else if (now - idleSince > 30_000_000_000L) {
                    synchronized (TIMER_LOCK) {
                        if (DIRTY.isEmpty()) {
                            timer = null;
                            return;
                        }
                    }
                }
                continue;
            }
            idleSince = 0L;
            forceDue(now, (long) delay * 1_000_000L);
        }
    }

    public static String stats() {
        return "region writes synced in batches: " + WRITES.sum() + " writes, " + FORCES.sum() + " forces (batch full " + FORCES_FULL.sum()
            + ", time up " + FORCES_DUE.sum() + "), vanilla flushes " + FLUSHES.sum() + ", files waiting " + dirty()
            + (enabled() ? ", delay " + delayMs() + " ms" : ", off")
            + (Double.isNaN(probedMs) ? "" : "; write-through probe " + String.format(Locale.ROOT, "%.2f", probedMs) + " ms per write -> "
            + (Boolean.TRUE.equals(worthIt) ? "batching" : "vanilla's write-through"));
    }
}
