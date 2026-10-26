package dev.denismasterherobrine.generatoraccelerator2.scheduling;

import com.sun.management.OperatingSystemMXBean;
import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.GeneratorAccelerator;
import dev.denismasterherobrine.generatoraccelerator2.gpu.light.GpuLight;
import dev.denismasterherobrine.generatoraccelerator2.light.LightLayers;
import dev.denismasterherobrine.generatoraccelerator2.light.LightTasks;
import dev.denismasterherobrine.generatoraccelerator2.parallellight.ParallelLightStats;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

/**
 * Pregeneration throughput benchmark: generates a square of chunks up to FULL and reports chunks per second,
 * process CPU, JIT and GC time over the run.
 *
 * <ul>
 * <li>{@code /ga2 bench pregen <chunkX> <chunkZ> <radius> [ticking]} requests the whole square at once, with one
 * region ticket per chunk at the FULL level (or entity ticking with {@code ticking}, like {@code /forceload}).
 * Vanilla's scheduler then advances every chunk one step at a time, so light (one thread) only starts at the end.</li>
 * <li>{@code /ga2 bench stream <chunkX> <chunkZ> <radius> <inFlight>} works like a pregenerator: chunks are requested
 * row by row with at most {@code inFlight} pending, and each chunk's ticket is dropped once it is FULL.</li>
 * </ul>
 * Completion is polled once per server tick, so times are accurate to about 50 ms. CPU time is also reported per
 * thread group (server thread, workers, light, chunk scheduler, IO), sampled once a second.
 */
public final class PregenBench {
    private PregenBench() {}

    private static final Logger LOGGER = Ga2Log.get("Bench");
    private static final TicketType<ChunkPos> TICKET = TicketType.create("ga2_bench", Comparator.comparingLong(ChunkPos::toLong));

    private static Run current;
    private static boolean listening;

    /** A run or probe still going when its server stopped would keep that server's level and refuse the next world's runs. */
    public static void onServerStopped() {
        current = null;
        probe = null;
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("bench")
            .then(Commands.literal("pregen")
                .then(Commands.argument("chunkX", IntegerArgumentType.integer())
                    .then(Commands.argument("chunkZ", IntegerArgumentType.integer())
                        .then(Commands.argument("radius", IntegerArgumentType.integer(0, 128))
                            .executes(ctx -> start(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "chunkX"),
                                IntegerArgumentType.getInteger(ctx, "chunkZ"), IntegerArgumentType.getInteger(ctx, "radius"), false, 0))
                            .then(Commands.literal("ticking")
                                .executes(ctx -> start(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "chunkX"),
                                    IntegerArgumentType.getInteger(ctx, "chunkZ"), IntegerArgumentType.getInteger(ctx, "radius"), true, 0)))))))
            .then(Commands.literal("stream")
                .then(Commands.argument("chunkX", IntegerArgumentType.integer())
                    .then(Commands.argument("chunkZ", IntegerArgumentType.integer())
                        .then(Commands.argument("radius", IntegerArgumentType.integer(0, 512))
                            .then(Commands.argument("inFlight", IntegerArgumentType.integer(1, 1 << 16))
                                .executes(ctx -> start(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "chunkX"),
                                    IntegerArgumentType.getInteger(ctx, "chunkZ"), IntegerArgumentType.getInteger(ctx, "radius"), false,
                                    IntegerArgumentType.getInteger(ctx, "inFlight"))))))))
            .then(ChunkHash.build())
            .then(Commands.literal("probe")
                .then(Commands.argument("chunkX", IntegerArgumentType.integer())
                    .then(Commands.argument("chunkZ", IntegerArgumentType.integer())
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                            .then(Commands.argument("spacing", IntegerArgumentType.integer(1, 512))
                                .executes(ctx -> probe(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "chunkX"),
                                    IntegerArgumentType.getInteger(ctx, "chunkZ"), IntegerArgumentType.getInteger(ctx, "count"),
                                    IntegerArgumentType.getInteger(ctx, "spacing"))))))))
            .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
            .then(Commands.literal("cancel").executes(ctx -> cancel(ctx.getSource())));
    }

    private static int start(CommandSourceStack source, int cx, int cz, int radius, boolean ticking, int inFlight) {
        if (current != null) {
            source.sendFailure(Component.literal("a bench run is already in progress (/ga2 bench status, /ga2 bench cancel)"));
            return 0;
        }
        if (!listening) {
            GeneratorAccelerator.addServerTickListener(PregenBench::onTick);
            listening = true;
        }
        Run run = new Run(source, source.getLevel(), cx, cz, radius, ticking, inFlight);
        current = run;
        if (StepTrace.ENABLED) {
            StepTrace.reset();
        }
        run.request();
        send(source, String.format(Locale.ROOT, "bench: generating %d chunks around chunk %d,%d (%s)", run.total, cx, cz,
            inFlight > 0 ? "streamed, at most " + inFlight + " pending" : ticking ? "entity ticking" : "full, not ticking"));
        return 1;
    }

    private static int status(CommandSourceStack source) {
        Run run = current;
        if (run == null) {
            send(source, "bench: idle");
        } else {
            send(source, String.format(Locale.ROOT, "bench: %d/%d chunks after %.1f s", run.done, run.total, (System.nanoTime() - run.t0) / 1e9));
        }
        return 1;
    }

    private static int cancel(CommandSourceStack source) {
        Run run = current;
        if (run != null) {
            run.removeAllTickets();
            current = null;
            send(source, "bench: cancelled");
        }
        return 1;
    }

    // ---- latency probe: how long a chunk a player stands in waits while other generation is going on

    private static final TicketType<ChunkPos> PROBE_TICKET = TicketType.create("ga2_probe", Comparator.comparingLong(ChunkPos::toLong));
    /** Ticket distance 2: level 31, a player's own chunks. */
    private static final int PROBE_DISTANCE = 2;
    private static Probe probe;

    private static int probe(CommandSourceStack source, int cx, int cz, int count, int spacing) {
        if (probe != null) {
            source.sendFailure(Component.literal("a probe is already running"));
            return 0;
        }
        if (!listening) {
            GeneratorAccelerator.addServerTickListener(PregenBench::onTick);
            listening = true;
        }
        probe = new Probe(source, source.getLevel(), cx, cz, count, spacing);
        probe.next();
        return 1;
    }

    /** Fresh chunks one after another along +x, each at a player chunk's ticket level. Reports the time until each is full. */
    private static final class Probe {
        final CommandSourceStack source;
        final ServerLevel level;
        final int cx;
        final int cz;
        final int count;
        final int spacing;
        final long[] millis;
        int index = -1;
        ChunkPos pos;
        long t0;

        Probe(CommandSourceStack source, ServerLevel level, int cx, int cz, int count, int spacing) {
            this.source = source;
            this.level = level;
            this.cx = cx;
            this.cz = cz;
            this.count = count;
            this.spacing = spacing;
            this.millis = new long[count];
        }

        void next() {
            this.index++;
            this.pos = new ChunkPos(this.cx + this.index * this.spacing, this.cz);
            this.t0 = System.nanoTime();
            this.level.getChunkSource().addRegionTicket(PROBE_TICKET, this.pos, PROBE_DISTANCE, this.pos);
        }

        /** @return true when every probe is done */
        boolean poll() {
            if (this.level.getChunkSource().getChunkNow(this.pos.x, this.pos.z) == null) {
                return false;
            }
            this.millis[this.index] = (System.nanoTime() - this.t0) / 1_000_000L;
            this.level.getChunkSource().removeRegionTicket(PROBE_TICKET, this.pos, PROBE_DISTANCE, this.pos);
            send(this.source, String.format(Locale.ROOT, "probe %d/%d: chunk %d,%d full after %d ms", this.index + 1, this.count, this.pos.x, this.pos.z,
                this.millis[this.index]));
            if (this.index + 1 >= this.count) {
                long[] sorted = this.millis.clone();
                Arrays.sort(sorted);
                send(this.source, String.format(Locale.ROOT, "probe: %d chunks, median %d ms, max %d ms (step lane %s)", this.count, sorted[sorted.length / 2],
                    sorted[sorted.length - 1], StepLane.ENABLED ? "on" : "off"));
                return true;
            }
            next();
            return false;
        }
    }

    private static void onTick(MinecraftServer server) {
        Probe p = probe;
        if (p != null && p.poll()) {
            probe = null;
        }
        Run run = current;
        if (run == null) {
            return;
        }
        if (run.poll()) {
            current = null;
            run.finish();
        }
    }

    private static void send(CommandSourceStack source, String line) {
        source.sendSuccess(() -> Component.literal(line), false);
        LOGGER.info(line);
    }

    private static final class Run {
        final CommandSourceStack source;
        final ServerLevel level;
        final int cx;
        final int cz;
        final int radius;
        final int side;
        final int distance;
        final int total;
        final int inFlight;
        /** 0 = not requested, 1 = ticket held, 2 = full (ticket dropped when streaming) */
        final byte[] state;
        int next;
        int pending;
        int done;
        int lastReported;
        final long t0;
        final long cpu0;
        final long jit0;
        final long gc0;
        final long surface0;
        final long features0;
        final long starts0;
        final long lightBatches0;
        final long[] lightRuns0;
        final long[] lightParts0;
        final long[] lightTasks0;
        final long[] lightLayers0;
        final long[] gpuLight0;
        final double[] locks0;
        final ThreadCpu threads = new ThreadCpu();
        int ticks;

        Run(CommandSourceStack source, ServerLevel level, int cx, int cz, int radius, boolean ticking, int inFlight) {
            this.source = source;
            this.level = level;
            this.cx = cx;
            this.cz = cz;
            this.radius = radius;
            this.side = 2 * radius + 1;
            this.distance = ticking ? 2 : 0;
            this.total = side * side;
            this.inFlight = inFlight;
            this.state = new byte[total];
            this.t0 = System.nanoTime();
            this.cpu0 = processCpu();
            this.jit0 = jit();
            this.gc0 = gc();
            this.surface0 = ParallelSteps.SURFACE_ASYNC.sum() + ParallelSteps.SURFACE_INLINE.sum();
            this.features0 = ParallelSteps.FEATURES_ASYNC.sum() + ParallelSteps.FEATURES_INLINE.sum();
            this.starts0 = ParallelSteps.STARTS_ASYNC.sum() + ParallelSteps.STARTS_INLINE.sum();
            this.lightBatches0 = ParallelLightStats.batchNanos();
            this.lightRuns0 = ParallelLightStats.runs();
            this.lightParts0 = ParallelLightStats.parts();
            this.lightTasks0 = LightTasks.snapshot();
            this.lightLayers0 = LightLayers.snapshot();
            this.gpuLight0 = GpuLight.snapshot();
            this.locks0 = AreaLocks.occupancy();
            ParallelLightStats.takeRunMax();
        }

        private ChunkPos pos(int i) {
            return new ChunkPos(cx - radius + i % side, cz - radius + i / side);
        }

        void request() {
            ServerChunkCache chunks = level.getChunkSource();
            int limit = inFlight > 0 ? inFlight : Integer.MAX_VALUE;
            while (next < total && pending < limit) {
                ChunkPos pos = pos(next);
                chunks.addRegionTicket(TICKET, pos, distance, pos);
                state[next] = 1;
                next++;
                pending++;
            }
        }

        void removeAllTickets() {
            ServerChunkCache chunks = level.getChunkSource();
            for (int i = 0; i < total; i++) {
                if (state[i] == 1 || (state[i] == 2 && inFlight == 0)) {
                    ChunkPos pos = pos(i);
                    chunks.removeRegionTicket(TICKET, pos, distance, pos);
                }
            }
        }

        /** @return true once every chunk of the square is a full chunk */
        boolean poll() {
            ServerChunkCache chunks = level.getChunkSource();
            for (int i = 0; i < next; i++) {
                if (state[i] == 1) {
                    ChunkPos pos = pos(i);
                    if (chunks.getChunkNow(pos.x, pos.z) != null) {
                        state[i] = 2;
                        done++;
                        pending--;
                        if (inFlight > 0) {
                            chunks.removeRegionTicket(TICKET, pos, distance, pos);
                        }
                    }
                }
            }
            if (inFlight > 0) {
                request();
            }
            if (++ticks % 20 == 0) {
                threads.sample();
            }
            double seconds = (System.nanoTime() - t0) / 1e9;
            if (seconds >= lastReported + 10) {
                lastReported = (int) seconds;
                LOGGER.info(String.format(Locale.ROOT, "%d/%d chunks (%.0f s)", done, total, seconds));
            }
            return done == total;
        }

        void finish() {
            double seconds = (System.nanoTime() - t0) / 1e9;
            double cpu = (processCpu() - cpu0) / 1e9;
            long surface = ParallelSteps.SURFACE_ASYNC.sum() + ParallelSteps.SURFACE_INLINE.sum() - surface0;
            long features = ParallelSteps.FEATURES_ASYNC.sum() + ParallelSteps.FEATURES_INLINE.sum() - features0;
            long starts = ParallelSteps.STARTS_ASYNC.sum() + ParallelSteps.STARTS_INLINE.sum() - starts0;
            send(source, String.format(Locale.ROOT,
                "bench: %d chunks in %.2f s = %.1f chunks/s; process CPU %.1f core-s (%.1f cores, %.1f ms/chunk); JIT %.1f s; GC %.2f s",
                total, seconds, total / seconds, cpu, cpu / seconds, 1000 * cpu / total, (jit() - jit0) / 1000.0, (gc() - gc0) / 1000.0));
            threads.sample();
            StringBuilder perThread = new StringBuilder("bench: thread CPU ms/chunk:");
            for (Map.Entry<String, Long> e : threads.groups.entrySet()) {
                perThread.append(String.format(Locale.ROOT, " %s %.2f,", e.getKey(), e.getValue() / 1e6 / total));
            }
            perThread.setLength(perThread.length() - 1);
            // wall time, not CPU: a light thread also waits while helpers finish a split batch
            perThread.append(String.format(Locale.ROOT, "; light update batches %.2f ms/chunk of light thread time",
                (ParallelLightStats.batchNanos() - lightBatches0) / 1e6 / total));
            long[] runs = ParallelLightStats.runs();
            long n = Math.max(1L, runs[0] - lightRuns0[0]);
            perThread.append(String.format(Locale.ROOT, "; whole light updates %d, %.2f ms each (longest %.1f), %.1f tasks each, %.2f ms idle before each",
                runs[0] - lightRuns0[0], (runs[1] - lightRuns0[1]) / 1e6 / n, ParallelLightStats.takeRunMax() / 1e6,
                (runs[2] - lightRuns0[2]) / (double) n, (runs[3] - lightRuns0[3]) / 1e6 / n));
            send(source, perThread.toString());
            send(source, "bench: " + ParallelLightStats.partsReport(lightParts0, total));
            send(source, "bench: " + LightTasks.report(lightTasks0, total));
            send(source, "bench: " + LightLayers.report(lightLayers0, total));
            send(source, "bench: " + GpuLight.report(gpuLight0, total));
            double[] locks = AreaLocks.occupancy();
            send(source, String.format(Locale.ROOT, "bench: steps run: structure starts %d, surface %d, features %d; on average %.1f decorations running under their area locks, %.1f light initializations, %.1f requests parked",
                starts, surface, features, (locks[0] - locks0[0]) / seconds, (locks[1] - locks0[1]) / seconds, (locks[2] - locks0[2]) / seconds));
            if (StepTrace.ENABLED) {
                send(source, "bench: " + StepTrace.report());
            }
            if (inFlight == 0) {
                removeAllTickets();
            }
        }
    }

    /**
     * CPU time of live threads by group since construction. Threads that exist at construction count from their
     * CPU time at that point. Threads started later count fully. A thread that ends loses at most its last second.
     */
    private static final class ThreadCpu {
        final ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        final Long2LongOpenHashMap last = new Long2LongOpenHashMap();
        final Long2ObjectOpenHashMap<String> group = new Long2ObjectOpenHashMap<>();
        final Map<String, Long> groups = new LinkedHashMap<>();

        ThreadCpu() {
            for (String g : new String[] {"server", "workers", "light", "lightHelpers", "scheduler", "io", "gpu", "other"}) {
                groups.put(g, 0L);
            }
            if (bean.isThreadCpuTimeSupported() && !bean.isThreadCpuTimeEnabled()) {
                bean.setThreadCpuTimeEnabled(true);
            }
            for (Thread t : live()) {
                long cpu = bean.getThreadCpuTime(t.threadId());
                if (cpu >= 0) {
                    last.put(t.threadId(), cpu);
                    group.put(t.threadId(), groupOf(t.getName()));
                }
            }
        }

        void sample() {
            for (Thread t : live()) {
                long id = t.threadId();
                long cpu = bean.getThreadCpuTime(id);
                if (cpu < 0) {
                    continue;
                }
                long before = last.getOrDefault(id, 0L);
                last.put(id, cpu);
                String g = group.computeIfAbsent(id, k -> groupOf(t.getName()));
                groups.merge(g, Math.max(0, cpu - before), Long::sum);
            }
        }

        private static Thread[] live() {
            ThreadGroup root = Thread.currentThread().getThreadGroup();
            while (root.getParent() != null) {
                root = root.getParent();
            }
            Thread[] threads = new Thread[root.activeCount() + 64];
            return Arrays.copyOf(threads, root.enumerate(threads, true));
        }

        private static String groupOf(String name) {
            if (name.equals("Server thread")) {
                return "server";
            } else if (name.startsWith("Worker-Main")) {
                return "workers";
            } else if (name.startsWith("GA2 Light Helper")) {
                return "lightHelpers";
            } else if (name.startsWith("GA2 GPU")) {
                return "gpu";
            } else if (name.startsWith("GA2 Light")) {
                return "light";
            } else if (name.startsWith("GA2 Chunk Scheduler")) {
                return "scheduler";
            } else if (name.startsWith("IO-Worker")) {
                return "io";
            }
            return "other";
        }
    }

    private static long processCpu() {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean os) {
            return os.getProcessCpuTime();
        }
        return 0;
    }

    private static long jit() {
        CompilationMXBean jit = ManagementFactory.getCompilationMXBean();
        return jit != null && jit.isCompilationTimeMonitoringSupported() ? jit.getTotalCompilationTime() : 0;
    }

    private static long gc() {
        long sum = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            sum += Math.max(0, bean.getCollectionTime());
        }
        return sum;
    }
}
