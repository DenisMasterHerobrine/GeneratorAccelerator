package dev.denismasterherobrine.generatoraccelerator2.logging;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.compat.CompatConfig;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToLongFunction;
import org.slf4j.Logger;

/**
 * Vanilla's world generation warnings about other mods' features and structures. They are not errors. A pack with one broken
 * feature can log the same warning thousands of times a minute. Most come from generation threads, and each line waits for
 * the log output. {@code worldgenWarnings} in {@code compat.toml} picks what is logged ({@link Mode}). Every warning is
 * counted by its source in every mode except {@link Mode#ALL}. Crash reports list the counts ({@code SystemReportMixin}).
 */
public final class RepeatedWarnings {
    private RepeatedWarnings() {}

    private static final Logger LOGGER = Ga2Log.get("Logs");

    public enum Kind {
        /** {@code WorldGenRegion.ensureCanWrite}: a feature or structure wrote outside the chunks its step may write to. */
        FAR_WRITE("far-chunk writes"),
        /** {@code PoiSection.add}: a point of interest of another type was already registered at the position. */
        POI_MISMATCH("POI data mismatches"),
        /** {@code WorldGenRegion.getBlockEntity}: a block entity block without its block entity. */
        MISSING_BLOCK_ENTITY("block entities read before they were made"),
        /** {@code LevelChunk.promotePendingBlockEntity}: a placeholder block entity on a block that has none. */
        DUMMY_BLOCK_ENTITY("placeholder block entities on blocks without one"),
        /** {@code LevelChunk.promotePendingBlockEntity}: a saved or placeholder block entity that could not be made. */
        FAILED_BLOCK_ENTITY("block entities that failed to load");

        final String what;

        Kind(String what) {
            this.what = what;
        }
    }

    public enum Mode {
        /** Nothing in the log. The first warning of each source goes to the debug log. */
        HIDDEN,
        /** The first warning of each source is logged. Repeats are summed up once a minute. */
        ONCE,
        /** Every warning is logged, as vanilla does. */
        ALL
    }

    /** What the caller does with one warning. */
    public enum Decision {
        /** Logs it as vanilla does. */
        LOG,
        /** Logs it at debug level ({@link #debug}). */
        DEBUG,
        /** Leaves it out. It is counted. */
        COUNT
    }

    /** Sources kept per kind. A kind with more sources counts the rest under one name. */
    private static final int MAX_SOURCES = 256;
    private static final String OTHER = "other";
    private static final long REPORT_NANOS = 60_000_000_000L;

    /** One source's warnings: those since the last summary, and all of them since the server started. */
    private static final class Count {
        final AtomicLong repeats = new AtomicLong();
        final AtomicLong total = new AtomicLong();
    }

    private static final Map<Kind, Map<String, Count>> COUNTS = new EnumMap<>(Kind.class);

    static {
        for (Kind kind : Kind.values()) {
            COUNTS.put(kind, new ConcurrentHashMap<>());
        }
    }

    private static volatile long lastReport = System.nanoTime();
    private static volatile String modeName;
    private static volatile Mode mode = Mode.HIDDEN;

    public static Mode mode() {
        String name = CompatConfig.worldgenWarnings();
        if (!name.equals(modeName)) {
            Mode parsed = switch (name.trim().toLowerCase(Locale.ROOT)) {
                case "once" -> Mode.ONCE;
                case "all" -> Mode.ALL;
                default -> Mode.HIDDEN;
            };
            mode = parsed;
            modeName = name;
        }
        return mode;
    }

    /** What to do with the warning of {@code kind} from {@code source}. Counts it unless every warning is logged. */
    public static Decision decide(Kind kind, String source) {
        Mode mode = mode();
        if (mode == Mode.ALL) {
            return Decision.LOG;
        }
        Map<String, Count> counts = COUNTS.get(kind);
        Count count = counts.get(source);
        boolean first = false;
        if (count == null) {
            if (counts.size() >= MAX_SOURCES) {
                count = counts.computeIfAbsent(OTHER, k -> new Count());
            } else {
                Count made = new Count();
                count = counts.putIfAbsent(source, made);
                if (count == null) {
                    count = made;
                    first = true;
                }
            }
        }
        count.total.incrementAndGet();
        if (first) {
            return mode == Mode.ONCE ? Decision.LOG : Decision.DEBUG;
        }
        count.repeats.incrementAndGet();
        return Decision.COUNT;
    }

    /** A warning kept out of the log, written at debug level in vanilla's words. */
    public static void debug(String format, Object... args) {
        LOGGER.debug(format, args);
    }

    /** The repeats of {@code source} not yet in a summary. -1 for a source not seen. */
    public static long repeats(Kind kind, String source) {
        Count count = COUNTS.get(kind).get(source);
        return count == null ? -1 : count.repeats.get();
    }

    /** At the end of a server tick. Sums up the repeats of the last minute. */
    public static void tick() {
        long now = System.nanoTime();
        if (now - lastReport >= REPORT_NANOS) {
            lastReport = now;
            report();
        }
    }

    /** Sums up the repeats since the last summary, and forgets the sources. The next server starts counting again. */
    public static void serverStopped() {
        report();
        for (Map<String, Count> counts : COUNTS.values()) {
            counts.clear();
        }
        lastReport = System.nanoTime();
    }

    private static void report() {
        boolean hidden = mode() != Mode.ONCE;
        for (Kind kind : Kind.values()) {
            String sources = describe(kind, c -> c.repeats.getAndSet(0));
            if (sources == null) {
                continue;
            }
            if (hidden) {
                LOGGER.debug("{}: {} -> not logged (worldgenWarnings in compat.toml)", kind.what, sources);
            } else {
                LOGGER.warn("{}: {} -> first of each source logged (all: worldgenWarnings = \"all\" in compat.toml)", kind.what, sources);
            }
        }
    }

    /** Every kind's warnings since the server started, for a crash report. Null when there were none. */
    public static String crashDetails() {
        StringBuilder out = new StringBuilder();
        for (Kind kind : Kind.values()) {
            String sources = describe(kind, c -> c.total.get());
            if (sources != null) {
                out.append("\n\t\t").append(kind.what).append(": ").append(sources);
            }
        }
        return out.isEmpty() ? null : out.toString();
    }

    /** {@code 1,234 [a x1,200, b x34]}: the count of {@code kind}'s sources and the five largest. Null for none. */
    private static String describe(Kind kind, ToLongFunction<Count> take) {
        List<Map.Entry<String, Long>> sources = new ArrayList<>();
        long total = 0;
        for (Map.Entry<String, Count> e : COUNTS.get(kind).entrySet()) {
            long n = take.applyAsLong(e.getValue());
            if (n > 0) {
                sources.add(Map.entry(e.getKey(), n));
                total += n;
            }
        }
        if (total == 0) {
            return null;
        }
        sources.sort(Map.Entry.<String, Long>comparingByValue().reversed());
        StringBuilder out = new StringBuilder(String.format(Locale.ROOT, "%,d [", total));
        for (int i = 0; i < Math.min(5, sources.size()); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(sources.get(i).getKey()).append(" x").append(String.format(Locale.ROOT, "%,d", sources.get(i).getValue()));
        }
        if (sources.size() > 5) {
            out.append(", ").append(sources.size() - 5).append(" more sources");
        }
        return out.append(']').toString();
    }

    /**
     * The placed feature or structure a generation region writes for, as the region names it ({@code currentlyGenerating}),
     * shortened from {@code ResourceKey[minecraft:worldgen/placed_feature / mod:name]} to {@code mod:name}. Null for none.
     */
    public static String generating(java.util.function.Supplier<String> currentlyGenerating) {
        if (currentlyGenerating == null) {
            return null;
        }
        String name = currentlyGenerating.get();
        int slash = name.lastIndexOf(" / ");
        if (name.startsWith("ResourceKey[") && slash >= 0 && name.endsWith("]")) {
            return name.substring(slash + 3, name.length() - 1);
        }
        return name;
    }
}
