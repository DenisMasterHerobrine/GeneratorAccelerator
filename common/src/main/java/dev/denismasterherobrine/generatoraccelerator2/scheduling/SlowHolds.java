package dev.denismasterherobrine.generatoraccelerator2.scheduling;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The longest few holds of an area lock: how long a step held its locks, how much of that it waited on its lane before it started, and
 * where. A hold of seconds keeps every step around it waiting that long. The list says whether the time went to the step's own work or
 * to its lane's queue. Cheap to offer to: a hold shorter than the shortest kept one takes no lock.
 */
public final class SlowHolds {
    private record Hold(long nanos, long queuedNanos, Object dimension, Object pos) {}

    private final Hold[] holds;
    private volatile long shortest;

    public SlowHolds(int size) {
        this.holds = new Hold[size];
    }

    /** One hold of {@code nanos}, of which {@code queuedNanos} before the step started, at {@code pos} in {@code dimension}. */
    public void offer(long nanos, long queuedNanos, Object dimension, Object pos) {
        if (nanos <= this.shortest) {
            return;
        }
        synchronized (this) {
            int at = -1;
            for (int i = 0; i < this.holds.length; i++) {
                if (this.holds[i] == null) {
                    at = i;
                    break;
                }
                if (at < 0 || this.holds[i].nanos < this.holds[at].nanos) {
                    at = i;
                }
            }
            if (this.holds[at] == null || this.holds[at].nanos < nanos) {
                this.holds[at] = new Hold(nanos, queuedNanos, dimension, pos);
            }
            long min = Long.MAX_VALUE;
            for (Hold h : this.holds) {
                min = Math.min(min, h == null ? 0 : h.nanos);
            }
            this.shortest = min;
        }
    }

    /** "1234 ms (queued 1200) at minecraft:overworld [10, -4]; ...", longest first, or "-" when none was offered. */
    public synchronized String describe() {
        List<Hold> list = new ArrayList<>();
        for (Hold h : this.holds) {
            if (h != null) {
                list.add(h);
            }
        }
        if (list.isEmpty()) {
            return "-";
        }
        list.sort((a, b) -> Long.compare(b.nanos, a.nanos));
        StringBuilder sb = new StringBuilder();
        for (Hold h : list) {
            sb.append(sb.isEmpty() ? "" : "; ").append(String.format(Locale.ROOT, "%.0f ms (queued %.0f) at %s %s",
                h.nanos / 1e6, h.queuedNanos / 1e6, h.dimension, h.pos));
        }
        return sb.toString();
    }

    public synchronized void reset() {
        Arrays.fill(this.holds, null);
        this.shortest = 0;
    }
}
