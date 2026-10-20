package dev.denismasterherobrine.generatoraccelerator2.chunkload;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import io.netty.buffer.Unpooled;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.slf4j.Logger;

/**
 * Encodes the block and biome data of a batch of chunk packets in parallel.
 *
 * <p>The server thread builds each chunk packet it sends. Most of a packet is the chunk's sections, each written into one
 * byte array ({@code ClientboundLevelChunkPacketData.extractChunkData}). In singleplayer, vanilla sends all chunks waiting
 * for the player in one batch. Here, once the batch is chosen, its sections are written ahead by GA2's packet threads and
 * the server thread together. The server thread waits until all are written. The packets then take the written bytes in
 * place of writing them again. Heightmaps, block entities and light are still built by the server thread, in vanilla's
 * order.
 *
 * <p>Exactness: the bytes come from the same section writes. Only the server thread changes a loaded chunk's sections, and
 * it waits while they are written. Each section counts its block and biome changes ({@link SectionVersion}). Bytes are
 * only taken while the chunk's count is still the one they were written at. A change in between, for example by another
 * mod's listener of the previous chunk's send, leaves the packet to vanilla's write.
 */
public final class ChunkPackets {
    private ChunkPackets() {}

    private static final Logger LOGGER = Ga2Log.get("ChunkLoad");
    private static final HookTargets.Hook HOOK = HookTargets.hook(HookTargets.CHUNK_PACKET_PREP);

    private record Prepared(byte[] bytes, long version) {}

    /** The batch the thread is sending. Server thread only. */
    private static final ThreadLocal<Map<LevelChunk, Prepared>> BATCH = new ThreadLocal<>();
    private static volatile ExecutorService pool;
    private static volatile boolean failed;

    public static boolean enabled() {
        return !failed && ChunkLoadConfig.parallelChunkPackets() && HOOK.allowed();
    }

    /** Writes the sections of {@code chunks} ahead. Batches of one chunk are left to vanilla. */
    public static void prepare(List<LevelChunk> chunks) {
        BATCH.remove();
        int n = chunks.size();
        if (n < 2 || !enabled()) {
            return;
        }
        long start = System.nanoTime();
        Prepared[] out = new Prepared[n];
        boolean finished = forEachParallel(n, i -> {
            LevelChunk chunk = chunks.get(i);
            try {
                LevelChunkSection[] sections = chunk.getSections();
                long version = version(sections);
                out[i] = new Prepared(encode(sections), version);
            } catch (RuntimeException e) {
                if (!failed) {
                    failed = true;
                    LOGGER.error("chunk packet encode failed at {} -> parallel chunk packets off", chunk.getPos(), e);
                }
            }
        });
        if (!finished) {
            return;
        }
        Map<LevelChunk, Prepared> batch = new IdentityHashMap<>(n * 2);
        for (int i = 0; i < n; i++) {
            if (out[i] != null) {
                batch.put(chunks.get(i), out[i]);
            }
        }
        ChunkLoadStats.PREPARED.add(batch.size());
        ChunkLoadStats.PREPARE.record(System.nanoTime() - start);
        BATCH.set(batch);
    }

    /** The section bytes written ahead for {@code chunk}, once, or null. */
    public static byte[] take(LevelChunk chunk) {
        Map<LevelChunk, Prepared> batch = BATCH.get();
        if (batch == null) {
            return null;
        }
        Prepared p = batch.remove(chunk);
        if (p == null) {
            return null;
        }
        if (version(chunk.getSections()) != p.version()) {
            ChunkLoadStats.PREPARED_STALE.increment();
            return null;
        }
        ChunkLoadStats.PREPARED_USED.increment();
        return p.bytes();
    }

    /** After the batch was sent. */
    public static void clear() {
        BATCH.remove();
    }

    /**
     * Runs {@code task} for 0 to n - 1 on the packet threads and this thread. Returns once all ran.
     *
     * <p>This thread takes items until none are left. It then waits only for the items a packet thread is still working
     * on. A packet thread that has not started yet finds nothing left and ends. So this thread never waits for a thread
     * the system has not scheduled. In singleplayer with ModernFix, the server thread and the worker pool run below
     * normal priority, and a thread of that priority can wait seconds for its turn while the client renders.
     */
    public static boolean forEachParallel(int n, java.util.function.IntConsumer task) {
        AtomicInteger next = new AtomicInteger();
        AtomicInteger left = new AtomicInteger(n);
        Runnable worker = () -> {
            int i;
            while ((i = next.getAndIncrement()) < n) {
                try {
                    task.accept(i);
                } finally {
                    left.decrementAndGet();
                }
            }
        };
        int helpers = Math.min(ChunkLoadConfig.packetThreads(), n - 1);
        if (helpers > 0) {
            ExecutorService threads = pool();
            for (int h = 0; h < helpers; h++) {
                threads.execute(worker);
            }
        }
        worker.run();
        while (left.get() > 0) {
            java.util.concurrent.locks.LockSupport.parkNanos(20_000L);
        }
        return true;
    }

    /** The sum of the sections' change counts. A change raises it. */
    public static long version(LevelChunkSection[] sections) {
        long v = 0;
        for (LevelChunkSection section : sections) {
            v += ((SectionVersion) section).ga2$version();
        }
        return v;
    }

    /** {@code calculateChunkSize} and {@code extractChunkData} of {@code ClientboundLevelChunkPacketData}. */
    public static byte[] encode(LevelChunkSection[] sections) {
        int size = 0;
        for (LevelChunkSection section : sections) {
            size += section.getSerializedSize();
        }
        byte[] bytes = new byte[size];
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        buf.writerIndex(0);
        for (LevelChunkSection section : sections) {
            section.write(buf);
        }
        return bytes;
    }

    /**
     * Daemon threads that exit after 30 s without work. They run at least at Java priority 6, above the worker pool and the
     * client's threads, and never below the thread that starts them. The server thread waits for the chunk each of them is
     * encoding, so they must not wait for a time slice. Each runs for a few milliseconds per batch.
     */
    private static ExecutorService pool() {
        ExecutorService p = pool;
        if (p == null) {
            synchronized (ChunkPackets.class) {
                p = pool;
                if (p == null) {
                    int threads = ChunkLoadConfig.packetThreads();
                    int priority = Math.max(Thread.currentThread().getPriority(), Thread.NORM_PRIORITY + 1);
                    AtomicInteger ids = new AtomicInteger();
                    ThreadPoolExecutor executor = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
                        Thread thread = new Thread(task, "GA2 Chunk Packets #" + ids.incrementAndGet());
                        thread.setDaemon(true);
                        thread.setPriority(priority);
                        return thread;
                    });
                    executor.allowCoreThreadTimeOut(true);
                    pool = p = executor;
                }
            }
        }
        return p;
    }
}
