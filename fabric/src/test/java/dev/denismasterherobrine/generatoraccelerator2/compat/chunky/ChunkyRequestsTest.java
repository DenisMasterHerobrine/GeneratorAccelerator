package dev.denismasterherobrine.generatoraccelerator2.compat.chunky;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The count of Chunky requests GA2 sets: the configured one, or one from the processors and the heap. */
class ChunkyRequestsTest {
    private static final long GB = 1L << 30;

    @Test
    void configuredCountWins() {
        assertEquals(50, ChunkyRequests.inFlight(50, 16, 6 * GB));
        assertEquals(2000, ChunkyRequests.inFlight(2000, 4, 2 * GB));
    }

    @Test
    void automaticCountFollowsProcessorsAndHeap() {
        assertEquals(256, ChunkyRequests.inFlight(0, 16, 16 * GB), "16 threads");
        assertEquals(64, ChunkyRequests.inFlight(0, 4, 4 * GB), "4 threads");
        assertEquals(170, ChunkyRequests.inFlight(0, 16, 4 * GB), "a 4 GB heap caps 16 threads");
        assertEquals(85, ChunkyRequests.inFlight(0, 32, 2 * GB), "a 2 GB heap caps a big CPU");
        assertEquals(1024, ChunkyRequests.inFlight(0, 128, 64 * GB), "at most 1024");
    }

    @Test
    void automaticCountNeverGoesBelowChunkysDefault() {
        assertEquals(50, ChunkyRequests.inFlight(0, 1, 4 * GB));
        assertEquals(50, ChunkyRequests.inFlight(0, 16, 256L << 20));
    }
}
