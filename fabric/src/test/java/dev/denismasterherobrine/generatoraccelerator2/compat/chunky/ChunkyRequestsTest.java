package dev.denismasterherobrine.generatoraccelerator2.compat.chunky;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The count of Chunky requests GA2 sets: the configured one, or one from the processors, the heap and the world's height. */
class ChunkyRequestsTest {
    private static final long GB = 1L << 30;
    /** A vanilla overworld, 384 blocks. */
    private static final int VANILLA = 24;

    @Test
    void configuredCountWins() {
        assertEquals(50, ChunkyRequests.inFlight(50, 16, 6 * GB, VANILLA));
        assertEquals(2000, ChunkyRequests.inFlight(2000, 4, 2 * GB, 48));
    }

    @Test
    void automaticCountFollowsProcessorsAndHeap() {
        assertEquals(256, ChunkyRequests.inFlight(0, 16, 16 * GB, VANILLA), "16 threads");
        assertEquals(64, ChunkyRequests.inFlight(0, 4, 4 * GB, VANILLA), "4 threads");
        assertEquals(170, ChunkyRequests.inFlight(0, 16, 4 * GB, VANILLA), "a 4 GB heap caps 16 threads");
        assertEquals(85, ChunkyRequests.inFlight(0, 32, 2 * GB, VANILLA), "a 2 GB heap caps a big CPU");
        assertEquals(1024, ChunkyRequests.inFlight(0, 128, 64 * GB, VANILLA), "at most 1024");
    }

    @Test
    void tallerWorldsFitFewerRequests() {
        assertEquals(256, ChunkyRequests.inFlight(0, 16, 6 * GB, VANILLA), "384 blocks");
        assertEquals(128, ChunkyRequests.inFlight(0, 16, 6 * GB, 48), "768 blocks need twice the heap per request");
        assertEquals(256, ChunkyRequests.inFlight(0, 16, 16 * GB, 48), "a big heap still reaches the processor count");
        assertEquals(256, ChunkyRequests.inFlight(0, 16, 6 * GB, 16), "a lower world keeps the vanilla figure");
    }

    @Test
    void automaticCountNeverGoesBelowChunkysDefault() {
        assertEquals(50, ChunkyRequests.inFlight(0, 1, 4 * GB, VANILLA));
        assertEquals(50, ChunkyRequests.inFlight(0, 16, 256L << 20, VANILLA));
        assertEquals(50, ChunkyRequests.inFlight(0, 16, 1 * GB, 128));
    }
}
