package dev.denismasterherobrine.generatoraccelerator2.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The chunk save backlog: high above the limit until it is below half of it, and the IO thread's write-along threshold. */
class WriteBacklogTest {
    @Test
    void highAboveTheLimitUntilBelowHalf() {
        assertFalse(WriteBacklog.next(false, 4096, 4096), "at the limit");
        assertTrue(WriteBacklog.next(false, 4097, 4096), "above it");
        assertTrue(WriteBacklog.next(true, 3000, 4096), "still above half");
        assertTrue(WriteBacklog.next(true, 2048, 4096), "at half");
        assertFalse(WriteBacklog.next(true, 2047, 4096), "below half");
        assertFalse(WriteBacklog.next(false, 3000, 4096), "rising again below the limit");
    }

    @Test
    void writeAlongFromHalfTheLimit() {
        assertEquals(2048, WriteBacklog.writeAlongFrom(4096));
        assertEquals(64, WriteBacklog.writeAlongFrom(100), "never below 64");
        assertEquals(Integer.MAX_VALUE, WriteBacklog.writeAlongFrom(0), "off");
    }

    @Test
    void storagesSumAndLeaveWhenClosed() {
        WriteBacklog.Storage a = () -> 3000;
        WriteBacklog.Storage b = () -> 2500;
        WriteBacklog.opened(a);
        WriteBacklog.opened(b);
        try {
            assertEquals(5500, WriteBacklog.total());
            assertTrue(WriteBacklog.high());
            WriteBacklog.closed(a);
            assertTrue(WriteBacklog.high(), "2500 is below the limit but not below half");
            WriteBacklog.closed(b);
            assertFalse(WriteBacklog.high());
        } finally {
            WriteBacklog.serverStopped();
        }
    }
}
