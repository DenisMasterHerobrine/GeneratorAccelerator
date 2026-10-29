package dev.denismasterherobrine.generatoraccelerator2.features;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.gpu.Gpu;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.slf4j.Logger;

/**
 * The decoration census ({@code /ga2 features census}). It measures where decoration spends its CPU time. It is off until started,
 * by the command or {@code -Dgeneratoraccelerator2.decorCensus=true}.
 *
 * <p>While it runs, every decoration is timed as a whole and its structure placements are timed apart. One top-level placement of
 * each thread in {@link #SAMPLE} is timed. The report gives each feature's and each feature type's share of the time.
 */
public final class FeatureCensus {
    private static final Logger LOGGER = Ga2Log.get("Features");
    /** Counting. Checked once per decoration and once per top-level placement. */
    public static volatile boolean on = Boolean.getBoolean("generatoraccelerator2.decorCensus");
    /** One top-level placement in this many is timed (a power of two). */
    private static final int SAMPLE = 16;

    private static final Map<ServerLevel, Dim> DIMS = Collections.synchronizedMap(new WeakHashMap<>());
    /** Raised by {@link #reset}. A thread holding the counts of an older one takes its dimension's new counts at the next decoration. */
    private static volatile int epoch;

    private FeatureCensus() {}

    /** One top-level placed feature of a dimension. */
    static final class Stat {
        final PlacedFeature feature;
        final LongAdder placements = new LongAdder();
        final LongAdder sampledNs = new LongAdder();
        final LongAdder samples = new LongAdder();

        Stat(PlacedFeature feature) {
            this.feature = feature;
        }

        /** Placements times the sampled mean. */
        double estimatedNs() {
            long n = samples.sum();
            return n == 0 ? 0 : (double) sampledNs.sum() / n * placements.sum();
        }
    }

    /** A dimension's counts. */
    static final class Dim {
        final WeakReference<ServerLevel> level;
        final String name;
        private final IdentityHashMap<PlacedFeature, Stat> stats = new IdentityHashMap<>();
        final LongAdder chunks = new LongAdder();
        final LongAdder decorationNs = new LongAdder();
        final LongAdder structureNs = new LongAdder();
        final LongAdder structurePlacements = new LongAdder();
        final LongAdder structureChunks = new LongAdder();

        Dim(ServerLevel level) {
            this.level = new WeakReference<>(level);
            this.name = level.dimension().identifier().toString();
        }

        synchronized Stat intern(PlacedFeature pf) {
            return stats.computeIfAbsent(pf, Stat::new);
        }

        synchronized List<Stat> stats() {
            return new ArrayList<>(stats.values());
        }
    }

    /** The decoration this thread is counting. */
    private static final class Local {
        boolean active;
        Dim dim;
        int epoch;
        IdentityHashMap<PlacedFeature, Stat> cache = new IdentityHashMap<>();
        long t0;
        boolean structures;
        int tick;
        Stat timed;
        long timedAt;
    }

    private static final ThreadLocal<Local> LOCAL = ThreadLocal.withInitial(Local::new);

    private static Dim dim(ServerLevel level) {
        synchronized (DIMS) {
            return DIMS.computeIfAbsent(level, Dim::new);
        }
    }

    // ---- hooks

    /** Before {@code applyBiomeDecoration}. */
    public static void chunkStart(WorldGenLevel region) {
        if (!on) {
            return;
        }
        ServerLevel level = region.getLevel();
        Local l = LOCAL.get();
        int e = epoch;
        if (l.dim == null || l.epoch != e || l.dim.level.get() != level) {
            l.dim = dim(level);
            l.epoch = e;
            l.cache.clear();
        }
        l.active = true;
        l.structures = false;
        l.timed = null;
        l.t0 = System.nanoTime();
    }

    /** After {@code applyBiomeDecoration}, also when it threw. */
    public static void chunkEnd() {
        Local l = LOCAL.get();
        if (!l.active) {
            return;
        }
        l.active = false;
        Dim d = l.dim;
        d.decorationNs.add(System.nanoTime() - l.t0);
        d.chunks.increment();
        if (l.structures) {
            d.structureChunks.increment();
        }
    }

    /** At a top-level placement ({@code PlacementPipeline.place} at nesting depth 0). */
    public static void featureStart(PlacedFeature pf) {
        Local l = LOCAL.get();
        if (!l.active) {
            return;
        }
        Stat s = l.cache.get(pf);
        if (s == null) {
            s = l.dim.intern(pf);
            l.cache.put(pf, s);
        }
        s.placements.increment();
        if ((++l.tick & (SAMPLE - 1)) == 0) {
            l.timed = s;
            l.timedAt = System.nanoTime();
        }
    }

    /** After the top-level placement {@link #featureStart} saw. */
    public static void featureEnd() {
        Local l = LOCAL.get();
        Stat s = l.timed;
        if (s != null) {
            l.timed = null;
            s.sampledNs.add(System.nanoTime() - l.timedAt);
            s.samples.increment();
        }
    }

    /** Before a structure start places its pieces in a decoration. Returns the start time, or 0 when not counting. */
    public static long structureStart() {
        Local l = LOCAL.get();
        if (!l.active) {
            return 0L;
        }
        l.structures = true;
        return System.nanoTime();
    }

    public static void structureEnd(long t0) {
        if (t0 != 0L) {
            Dim d = LOCAL.get().dim;
            d.structureNs.add(System.nanoTime() - t0);
            d.structurePlacements.increment();
        }
    }

    // ---- commands

    public static void reset() {
        synchronized (DIMS) {
            DIMS.clear();
            epoch++;
        }
    }

    public static String status() {
        long chunks = 0;
        synchronized (DIMS) {
            for (Dim d : DIMS.values()) {
                chunks += d.chunks.sum();
            }
        }
        return "decoration census " + (on ? "on" : "off") + ", " + chunks + " chunks counted";
    }

    /** Hands the report's lines to {@code out}. Each dimension's full list goes to {@code <game dir>/.ga2/decor-census-<dimension>.txt}. */
    public static void report(Consumer<String> out) {
        List<Dim> dims;
        synchronized (DIMS) {
            dims = new ArrayList<>(DIMS.values());
        }
        if (dims.isEmpty()) {
            out.accept(status() + ". Start it with /ga2 features census on, then generate chunks.");
            return;
        }
        for (Dim d : dims) {
            try {
                report(d).forEach(out);
            } catch (RuntimeException e) {
                LOGGER.warn("census report of {} failed", d.name, e);
                out.accept(d.name + ": report failed: " + e);
            }
        }
    }

    private static List<String> report(Dim d) {
        ServerLevel level = d.level.get();
        List<String> lines = new ArrayList<>();
        long chunks = d.chunks.sum();
        if (level == null || chunks == 0) {
            return lines;
        }
        List<Stat> stats = d.stats();
        double total = 0;
        Map<String, Double> byType = new HashMap<>();
        for (Stat s : stats) {
            double ns = s.estimatedNs();
            total += ns;
            byType.merge(type(s.feature), ns, Double::sum);
        }
        double decoration = d.decorationNs.sum();
        double structures = d.structureNs.sum();
        lines.add(String.format(Locale.ROOT, "%s: %d chunks, decoration %.2f ms/chunk (features %.2f, structures %.2f, rest %.2f); structures in %.1f%% of chunks",
            d.name, chunks, decoration / chunks / 1e6, total / chunks / 1e6, structures / chunks / 1e6, Math.max(0, decoration - total - structures) / chunks / 1e6,
            pct(d.structureChunks.sum(), chunks)));
        stats.sort(Comparator.comparingDouble(Stat::estimatedNs).reversed());
        List<String> file = new ArrayList<>(lines);
        file.add("");
        file.add("share  ms/chunk  ns/placement  placements  feature  type");
        for (int i = 0; i < stats.size(); i++) {
            Stat s = stats.get(i);
            long k = s.samples.sum();
            String row = String.format(Locale.ROOT, "%5.1f%%  %.3f  %s  %d  %s  %s", pct(s.estimatedNs(), decoration), s.estimatedNs() / chunks / 1e6,
                k == 0 ? "-" : String.format(Locale.ROOT, "%.0f", (double) s.sampledNs.sum() / k), s.placements.sum(), name(level, s.feature), type(s.feature));
            file.add(row);
            if (i < 15) {
                lines.add("  " + row);
            }
        }
        file.add("");
        file.add("by feature type (share):");
        List<Map.Entry<String, Double>> types = new ArrayList<>(byType.entrySet());
        types.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        StringBuilder top = new StringBuilder("  by type:");
        for (int i = 0; i < types.size(); i++) {
            var e = types.get(i);
            String row = String.format(Locale.ROOT, "%s %.1f%%", e.getKey(), pct(e.getValue(), decoration));
            file.add("  " + row);
            if (i < 8) {
                top.append(i == 0 ? " " : ", ").append(row);
            }
        }
        lines.add(top.toString());
        Path f = Gpu.dataDir()
            .resolve("decor-census-" + d.name.replace(':', '_').replace('/', '_') + ".txt");
        try {
            Files.createDirectories(f.getParent());
            Files.write(f, file, StandardCharsets.UTF_8);
            lines.add("  full list: " + f);
        } catch (Exception e) {
            LOGGER.warn("{}: write failed", f, e);
        }
        LOGGER.info("decoration census {} -> {}", d.name, f);
        return lines;
    }

    private static String type(PlacedFeature pf) {
        Identifier key = BuiltInRegistries.FEATURE.getKey(pf.feature().value().feature());
        return key != null ? key.toString() : pf.feature().value().feature().getClass().getName();
    }

    private static String name(ServerLevel level, PlacedFeature pf) {
        Identifier key = level.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE).getKey(pf);
        if (key != null) {
            return key.toString();
        }
        return pf.feature().unwrapKey().map(k -> "inline " + k.identifier()).orElse("inline " + type(pf));
    }

    private static double pct(double part, double whole) {
        return whole <= 0 ? 0 : 100.0 * part / whole;
    }
}
