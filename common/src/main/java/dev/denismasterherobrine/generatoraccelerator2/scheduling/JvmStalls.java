package dev.denismasterherobrine.generatoraccelerator2.scheduling;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * The time the whole JVM stood still: collector pauses, other safepoints, the OS holding the process up. Every thread stops then,
 * so a wait that ran through such a stall says nothing about what it waited for. GA2's waits for the GPU leave this time out
 * ({@link Wait}). A GPU answer may have been in all along, with no thread running to take it.
 *
 * <p>A daemon thread sleeps {@link #STEP_NS} at a time. A wake-up more than {@link #SLACK_NS} late adds its lateness to the total.
 * The slack covers Windows' 15.6 ms timer ticks. Shorter stalls are not counted. The first {@link Wait} starts the thread.
 */
public final class JvmStalls {
    private JvmStalls() {}

    private static final long STEP_NS = 10_000_000L;
    private static final long SLACK_NS = 25_000_000L;
    /** How long a wait gives the watch to count a stall the JVM may just have gone on from. */
    private static final long CATCH_UP_NS = STEP_NS + SLACK_NS;
    /** How often one wait gives the watch that time. */
    private static final int MAX_CATCH_UPS = 4;

    private static final AtomicLong TOTAL = new AtomicLong();
    /** When the watch last woke, written by it after {@link #TOTAL}. */
    private static volatile long lastWake;
    private static volatile boolean started;
    /** Waits that stalls made longer than their time. */
    public static final LongAdder STRETCHED = new LongAdder();

    /** Nanoseconds the JVM stood still since the watch started. 0 before it starts. */
    public static long total() {
        return TOTAL.get();
    }

    /**
     * False while the watch has not woken since a stall that may still be missing from {@link #total}. The watch and the waiting
     * threads go on at the same moment after a stall, and the watch may count it a little later.
     */
    public static boolean current() {
        return !started || System.nanoTime() - lastWake <= CATCH_UP_NS;
    }

    private static void start() {
        if (started) {
            return;
        }
        synchronized (JvmStalls.class) {
            if (started) {
                return;
            }
            lastWake = System.nanoTime();
            Thread t = new Thread(JvmStalls::watch, "GA2 stall watch");
            t.setDaemon(true);
            // a late wake-up should mean a stall, not other threads busy on every core
            t.setPriority(Thread.MAX_PRIORITY);
            t.start();
            started = true;
        }
    }

    private static void watch() {
        long before = System.nanoTime();
        while (true) {
            LockSupport.parkNanos(STEP_NS);
            long now = System.nanoTime();
            long late = now - before - STEP_NS;
            if (late > SLACK_NS) {
                TOTAL.addAndGet(late);
            }
            lastWake = now;
            before = now;
        }
    }

    /** A wait of a given time that leaves out the stalls while it runs. One thread at a time uses it. */
    public static final class Wait {
        private final long start;
        private final long end;
        private int catchUps;
        private boolean stretched;

        public Wait(long nanos) {
            JvmStalls.start();
            start = total();
            end = System.nanoTime() + nanos;
        }

        /**
         * The nanoseconds this wait still has: its time from its start, plus the stalls since then. 0 once it is over. Just after a
         * stall the watch may not have counted it yet. The wait then gets the watch's catch-up time.
         */
        public long left() {
            // whether the watch is current first: a total read after it holds every stall up to the watch's last wake-up
            boolean current = current();
            long now = System.nanoTime();
            long left = end + (total() - start) - now;
            if (left <= 0 && !current && catchUps < MAX_CATCH_UPS) {
                catchUps++;
                left = CATCH_UP_NS;
            }
            if (left <= 0) {
                return 0;
            }
            if (!stretched && now - end >= 0) {
                stretched = true;
                STRETCHED.increment();
            }
            return left;
        }
    }
}
