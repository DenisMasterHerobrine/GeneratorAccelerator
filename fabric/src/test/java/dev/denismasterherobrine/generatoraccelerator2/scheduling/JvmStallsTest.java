package dev.denismasterherobrine.generatoraccelerator2.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.GpuWorldgen;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;

/**
 * {@link JvmStalls}: a wait leaves out the time the whole JVM stood still. The stalls here are added as the watch would add them
 * through its counter, so a real pause of the test JVM can only lengthen the waits.
 */
class JvmStallsTest {
    private static final long MS = 1_000_000L;

    /** Adds a stall to the watch's total, as the watch would count one. A wait starts the watch first. */
    private static void stalled(long nanos) {
        new JvmStalls.Wait(0);
        try {
            Field total = JvmStalls.class.getDeclaredField("TOTAL");
            total.setAccessible(true);
            ((AtomicLong) total.get(null)).addAndGet(nanos);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void aWaitWithoutStallsEndsOnTime() throws Exception {
        JvmStalls.Wait wait = new JvmStalls.Wait(50 * MS);
        long left = wait.left();
        assertTrue(left > 30 * MS && left <= 50 * MS, "a new wait has its time: " + left);
        Thread.sleep(120);
        assertEquals(0, wait.left(), "the time is up");
    }

    @Test
    void stallsLengthenAWait() throws Exception {
        long stretched = JvmStalls.STRETCHED.sum();
        JvmStalls.Wait wait = new JvmStalls.Wait(50 * MS);
        stalled(300 * MS);
        Thread.sleep(80);
        long left = wait.left();
        assertTrue(left > 150 * MS && left <= 300 * MS, "the stall is added to the wait: " + left);
        assertEquals(stretched + 1, JvmStalls.STRETCHED.sum(), "the wait counts as made longer");
    }

    @Test
    void stallsBeforeAWaitDoNotCount() throws Exception {
        stalled(300 * MS);
        JvmStalls.Wait wait = new JvmStalls.Wait(50 * MS);
        Thread.sleep(120);
        assertEquals(0, wait.left());
    }

    @Test
    void aDeadlineRunsWhenNoAnswerComes() throws Exception {
        AtomicBoolean late = new AtomicBoolean();
        GpuWorldgen.deadline(new CompletableFuture<Void>(), 50, () -> late.set(true));
        Thread.sleep(300);
        assertTrue(late.get());
    }

    @Test
    void aDeadlineWaitsOutAStall() throws Exception {
        AtomicBoolean late = new AtomicBoolean();
        CompletableFuture<Void> answer = new CompletableFuture<>();
        GpuWorldgen.deadline(answer, 50, () -> late.set(true));
        stalled(400 * MS);
        Thread.sleep(200);
        assertFalse(late.get(), "the answer still has the time of the stall");
        answer.complete(null);
        Thread.sleep(400);
        assertFalse(late.get(), "the answer cancelled the deadline");
    }

    @Test
    void aStallLengthensAQuietWait() throws Exception {
        CompletableFuture<String> answer = new CompletableFuture<>();
        Thread helper = new Thread(() -> {
            try {
                Thread.sleep(20);
                stalled(400 * MS);
                Thread.sleep(130);
                answer.complete("in");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        helper.start();
        assertEquals("in", QuietWait.get(answer, 50));
        helper.join();
        assertThrows(TimeoutException.class, () -> QuietWait.get(new CompletableFuture<>(), 50));
    }
}
