package dev.denismasterherobrine.generatoraccelerator2.parallellight;

import dev.denismasterherobrine.generatoraccelerator2.parallellight.mixin.LayerLightSectionStorageAccessor;
import dev.denismasterherobrine.generatoraccelerator2.parallellight.mixin.LightEngineAccessor;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.lighting.BlockLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.lighting.SkyLightEngine;

/**
 * Parallel light propagation for server light engines.
 *
 * <p>Vanilla lights a dimension on one thread. The light tasks (section changes, a new chunk's light sources, block
 * changes) run in order. Then one batch of propagation spreads and removes light, and the batch ends. All of that still
 * runs on the light thread in vanilla's order. Only the propagation of a large batch is split by section between the light
 * thread and helper threads ({@link PropagationJob}). Vanilla's propagation reaches the same light in whatever order the
 * queue entries are taken. The stored levels are re-checked when an entry is taken, and decreases finish before
 * increases start. The light is therefore identical. The order of the "light changed" notifications to the chunk holders
 * may differ. That order changes nothing the holders send.
 *
 * <p>Self-contained. It reaches vanilla through its own accessors ({@code parallellight.mixin}) and needs nothing else from
 * Generator Accelerator 2. The installer decides through {@link #setCompatGate} whether other mods' changes to the light
 * engine allow it.
 */
public final class ParallelLight {
    private ParallelLight() {}

    /** {@code -Dgeneratoraccelerator2.parallelLight=false} turns it off. Not final, so tests can compare both ways. */
    public static boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.parallelLight"));
    /** Tests: also engines that do not belong to a server level. */
    public static volatile boolean anyEngine;
    /** Tests: helper threads and batch threshold instead of the config's (-1 = config). */
    public static volatile int helpersOverride = -1;
    public static volatile int minEntriesOverride = -1;

    private static volatile BooleanSupplier compatGate = () -> true;

    /**
     * Another way to run a batch's increase propagation, set by the installer (Generator Accelerator 2 runs it on the GPU). When it
     * wants a batch, the decreases run here first, then the offload runs the increases. It returns -1 with the queue as it was,
     * and the increases then run here.
     */
    public interface IncreaseOffload {
        /** Whether to offer this batch: {@code increases} and {@code decreases} queued entries after the node checks. */
        boolean wants(LightEngine<?, ?> engine, boolean sky, int increases, int decreases);

        /** Runs the engine's queued increases. Returns the entries processed, or -1 with the queue untouched. */
        int propagateIncreases(LightEngine<?, ?> engine, boolean sky);

        /** The light thread's time for {@code increases} queued entries the increase propagation here ran. */
        void cpuIncreases(boolean sky, int increases, long nanos);
    }

    private static volatile IncreaseOffload offload;

    /** Sets the increase offload ({@link IncreaseOffload}), or null for none. */
    public static void setIncreaseOffload(IncreaseOffload o) {
        offload = o;
    }

    /** Whether other mods' changes to the light engine leave it on (checked per batch). */
    public static void setCompatGate(BooleanSupplier gate) {
        compatGate = gate;
    }

    /**
     * Replaces {@code LightEngine.runLightUpdates}. Runs vanilla's node checks, the propagation (split when worthwhile) and
     * vanilla's end of the batch. Returns -1 to let vanilla's method run instead, for other engines or when turned off.
     */
    public static int runLightUpdates(LightEngine<?, ?> engine) {
        if (!ENABLED) {
            return -1;
        }
        Class<?> type = engine.getClass();
        boolean sky = type == SkyLightEngine.class;
        if (!sky && type != BlockLightEngine.class) {
            return -1;
        }
        LightEngineAccessor e = (LightEngineAccessor) engine;
        if (!anyEngine && !(e.ga2pl$chunkSource() instanceof ServerChunkCache)) {
            return -1;
        }
        if (!ParallelLightConfig.enabled() || !compatGate.getAsBoolean()) {
            return -1;
        }
        int layer = sky ? 1 : 0;
        long t0 = System.nanoTime();
        LongOpenHashSet nodes = e.ga2pl$blockNodesToCheck();
        LongIterator it = nodes.iterator();
        while (it.hasNext()) {
            e.ga2pl$checkNode(it.nextLong());
        }
        nodes.clear();
        nodes.trim(512);
        int queued = (e.ga2pl$decreaseQueue().size() + e.ga2pl$increaseQueue().size()) / 2;
        int processed = -1;
        IncreaseOffload off = offload;
        int increasesQueued = e.ga2pl$increaseQueue().size() / 2;
        long t1 = System.nanoTime();
        ParallelLightStats.part(layer, ParallelLightStats.CHECKS, t1 - t0);
        ParallelLightStats.queued(layer, queued);
        if (off != null && increasesQueued > 0 && off.wants(engine, sky, increasesQueued, e.ga2pl$decreaseQueue().size() / 2)) {
            // decreases first, as vanilla runs them, then the offload's increases
            processed = e.ga2pl$propagateDecreases();
            long t2 = System.nanoTime();
            ParallelLightStats.part(layer, ParallelLightStats.DECREASES, t2 - t1);
            int increases = off.propagateIncreases(engine, sky);
            long t3 = System.nanoTime();
            ParallelLightStats.part(layer, ParallelLightStats.OFFLOAD, t3 - t2);
            if (increases < 0) {
                increases = timedIncreases(e, off, sky);
                ParallelLightStats.part(layer, ParallelLightStats.INCREASES, System.nanoTime() - t3);
            }
            processed += increases;
            endBatch(engine, e, layer);
            return processed;
        }
        int min = minEntriesOverride >= 0 ? minEntriesOverride : ParallelLightConfig.minEntries();
        // A block light entry is mostly a light source flooding up to 15 blocks around. A sky light entry is a short step.
        long work = sky ? queued : queued * 16L;
        if (queued > 0 && work >= min) {
            int helpers = helpersOverride >= 0 ? helpersOverride : ParallelLightConfig.threads();
            if (helpers > 0 && minEntriesOverride < 0 && busy(e) < ParallelLightConfig.minBusy()) {
                // The light thread keeps up on its own. Helpers would only take processor time from world generation.
                helpers = 0;
                ParallelLightStats.KEEPING_UP.increment();
            }
            if (helpers > 0) {
                processed = PropagationJob.run(e, sky, helpers);
                if (processed < 0) {
                    ParallelLightStats.NO_HELPER.increment();
                } else {
                    ParallelLightStats.part(layer, ParallelLightStats.SPLIT, System.nanoTime() - t1);
                }
            }
        }
        if (processed < 0) {
            if (queued > 0) {
                ParallelLightStats.SERIAL_BATCHES.increment();
                ParallelLightStats.SERIAL_QUEUED.add(queued);
            }
            long t2 = System.nanoTime();
            processed = e.ga2pl$propagateDecreases();
            long t3 = System.nanoTime();
            ParallelLightStats.part(layer, ParallelLightStats.DECREASES, t3 - t2);
            processed += off != null ? timedIncreases(e, off, sky) : e.ga2pl$propagateIncreases();
            ParallelLightStats.part(layer, ParallelLightStats.INCREASES, System.nanoTime() - t3);
            ParallelLightStats.SERIAL_PROCESSED.add(processed);
        }
        endBatch(engine, e, layer);
        return processed;
    }

    /** Vanilla's end of a batch: the chunk cache emptied, the sections' changes settled, the light published. Timed. */
    private static void endBatch(LightEngine<?, ?> engine, LightEngineAccessor e, int layer) {
        long t0 = System.nanoTime();
        e.ga2pl$clearChunkCache();
        LayerLightSectionStorageAccessor storage = (LayerLightSectionStorageAccessor) e.ga2pl$storage();
        storage.ga2pl$markNewInconsistencies(engine);
        storage.ga2pl$swapSectionMap();
        ParallelLightStats.part(layer, ParallelLightStats.END, System.nanoTime() - t0);
    }

    /** The engine's own increase propagation, timed for the offload's choice between it and the offload. */
    private static int timedIncreases(LightEngineAccessor e, IncreaseOffload off, boolean sky) {
        int entries = e.ga2pl$increaseQueue().size() / 2;
        long t0 = System.nanoTime();
        int n = e.ga2pl$propagateIncreases();
        if (entries > 0) {
            off.cpuIncreases(sky, entries, System.nanoTime() - t0);
        }
        return n;
    }

    /** The busy share of the light thread that runs this engine, or 1 when the share is unknown. */
    private static double busy(LightEngineAccessor engine) {
        return engine.ga2pl$chunkSource() instanceof ServerChunkCache cache && cache.getLightEngine() instanceof LightLoad load ? load.ga2pl$busy() : 1.0;
    }

    /** Status lines for a command. */
    public static List<String> statusLines() {
        List<String> lines = ParallelLightStats.lines();
        lines.add(0, "parallel light: " + (!ENABLED ? "off (-Dgeneratoraccelerator2.parallelLight=false)"
            : !ParallelLightConfig.enabled() ? "off (config)"
            : !compatGate.getAsBoolean() ? "off (another mod changes the light engine)"
            : "on, " + ParallelLightConfig.threads() + " helper threads, batches of at least " + ParallelLightConfig.minEntries()
                + " entries while the light thread is busy at least " + Math.round(ParallelLightConfig.minBusy() * 100) + "% of the time"));
        return lines;
    }
}
