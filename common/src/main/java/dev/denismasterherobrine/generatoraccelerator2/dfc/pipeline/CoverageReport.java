package dev.denismasterherobrine.generatoraccelerator2.dfc.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.slf4j.Logger;

/**
 * Records every density function the compiler could not fully compile, with enough detail to
 * add a lowering: mod id, class, router field, tree path and the reason. One entry per class.
 */
public final class CoverageReport {
    public enum Kind { LOWERED_BY_API, LIFTED, FALLBACK, INTERPRETER, PENDING_PORT }

    public static final class Entry {
        public final Kind kind;
        public final String className;
        public final String modId;
        public final String root;
        public final String path;
        public final String reason;
        public final AtomicLong count = new AtomicLong();

        Entry(Kind kind, String className, String modId, String root, String path, String reason) {
            this.kind = kind;
            this.className = className;
            this.modId = modId;
            this.root = root;
            this.path = path;
            this.reason = reason;
        }

        public String line() {
            return kind + " " + className + " [mod=" + modId + "] at " + root + path + " x" + count.get() + (reason.isEmpty() ? "" : " : " + reason);
        }
    }

    private static final CoverageReport GLOBAL = new CoverageReport();

    public static CoverageReport global() {
        return GLOBAL;
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public void unsupported(DensityFunction fn, String root, String path, String reason) {
        add(Kind.FALLBACK, fn, root, path, reason);
    }

    public void lowered(DensityFunction fn, String root, String path) {
        add(Kind.LOWERED_BY_API, fn, root, path, "");
    }

    public void lifted(DensityFunction fn, String root, String path) {
        add(Kind.LIFTED, fn, root, path, "");
    }

    /** A cache marker that stays on its vanilla wrapper inside compiled code because it carries per-chunk state. */
    public void statefulMarker(DensityFunction marker, String root, String kind) {
        statefulMarker(marker, root, kind, "");
    }

    /** As {@link #statefulMarker(DensityFunction, String, String)}, with why the marker cannot be emulated ({@code why} may be empty). */
    public void statefulMarker(DensityFunction marker, String root, String kind, String why) {
        String reason = "vanilla NoiseChunk wrapper (exact)" + (why.isEmpty() ? "; no emulation yet" : ": " + why);
        entries.computeIfAbsent("stateful:" + kind + ":" + why, k -> new Entry(Kind.PENDING_PORT, "stateful " + kind + " marker", "minecraft", root, "/", reason)).count.incrementAndGet();
    }

    public void interpreterRouted(String root, String reason) {
        entries.computeIfAbsent("root:" + root, k -> new Entry(Kind.INTERPRETER, root, "minecraft", root, "/", reason)).count.incrementAndGet();
    }

    private void add(Kind kind, DensityFunction fn, String root, String path, String reason) {
        String cls = fn.getClass().getName();
        entries.computeIfAbsent(kind + ":" + cls, k -> new Entry(kind, cls, ModOrigin.modIdOf(fn.getClass()), root, path, reason)).count.incrementAndGet();
    }

    public boolean hasFallbacks() {
        return entries.values().stream().anyMatch(e -> e.kind == Kind.FALLBACK || e.kind == Kind.INTERPRETER);
    }

    public List<Entry> entries() {
        return new ArrayList<>(entries.values());
    }

    public List<String> lines() {
        List<String> out = new ArrayList<>();
        for (Entry e : entries.values()) {
            out.add(e.line());
        }
        if (out.isEmpty()) {
            out.add("all compiled, no fallbacks");
        }
        return out;
    }

    public void logSummary(Logger log, String what) {
        boolean bad = false;
        for (Entry e : entries.values()) {
            if (e.kind == Kind.FALLBACK || e.kind == Kind.INTERPRETER) {
                bad = true;
                log.warn("{}: {}", what, e.line());
            } else {
                log.info("{}: {}", what, e.line());
            }
        }
        if (!bad) {
            log.info("{}: all compiled, no fallbacks", what);
        }
    }

    /** Merges another report into this one (used to accumulate per-compile reports into the global one). */
    public void mergeInto(CoverageReport target) {
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            Entry src = e.getValue();
            Entry dst = target.entries.computeIfAbsent(e.getKey(), k -> new Entry(src.kind, src.className, src.modId, src.root, src.path, src.reason));
            dst.count.addAndGet(src.count.get());
        }
    }
}
