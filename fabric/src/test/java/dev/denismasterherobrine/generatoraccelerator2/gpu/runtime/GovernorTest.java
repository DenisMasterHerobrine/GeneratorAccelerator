package dev.denismasterherobrine.generatoraccelerator2.gpu.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** {@link GpuWorldgen.Governor}: many late answers pause a dimension's asks, and answers from before a pause do not pause it again. */
class GovernorTest {
    private static final long PAUSE_NS = 50_000_000L;

    @Test
    void manyLateAnswersPause() {
        GpuWorldgen.Governor g = new GpuWorldgen.Governor("test", PAUSE_NS);
        for (int i = 0; i < 59; i++) {
            g.answered(false);
        }
        for (int i = 0; i < 5; i++) {
            g.answered(true);
        }
        assertFalse(g.asking(), "5 of 64 late");
    }

    @Test
    void fewLateAnswersDoNotPause() {
        GpuWorldgen.Governor g = new GpuWorldgen.Governor("test", PAUSE_NS);
        for (int i = 0; i < 60; i++) {
            g.answered(false);
        }
        for (int i = 0; i < 4; i++) {
            g.answered(true);
        }
        assertTrue(g.asking(), "4 of 64 late");
    }

    @Test
    void answersFromBeforeAPauseDoNotPauseAgain() throws Exception {
        GpuWorldgen.Governor g = new GpuWorldgen.Governor("test", PAUSE_NS);
        for (int i = 0; i < 64; i++) {
            g.answered(true);
        }
        assertFalse(g.asking());
        // the asks made before the pause come back late while it lasts
        for (int i = 0; i < 40; i++) {
            g.answered(true);
        }
        Thread.sleep(100);
        assertTrue(g.asking(), "the pause is over");
        for (int i = 0; i < 64; i++) {
            g.answered(false);
        }
        assertTrue(g.asking(), "the window started empty after the pause");
    }

    /** Late windows in a row double the pause up to the longest one. A window with few late answers starts the next pause short. */
    @Test
    void pausesGrowWhileWindowsStayLate() throws Exception {
        long first = 10_000_000L, longest = 35_000_000L;
        GpuWorldgen.Governor g = new GpuWorldgen.Governor("test", "chunks", "blocks", longest, first);
        long[] expected = { first, 2 * first, longest, longest };
        for (long pause : expected) {
            lateWindow(g);
            assertFalse(g.asking(), "paused");
            Assertions.assertEquals(pause, g.lastPauseNs, "pause after another late window");
            waitOut(g);
        }
        // a clean window, then a late one: the pause is the first one again
        for (int i = 0; i < 64; i++) {
            g.answered(false);
        }
        lateWindow(g);
        Assertions.assertEquals(first, g.lastPauseNs, "after a clean window");
    }

    /** The pause this rule exists for still comes: a GPU late in every window is paused for the longest time within a few windows. */
    @Test
    void aSlowGpuReachesTheLongestPause() throws Exception {
        long first = 5_000_000L, longest = 40_000_000L;
        GpuWorldgen.Governor g = new GpuWorldgen.Governor("test", "chunks", "blocks", longest, first);
        int windows = 0;
        while (g.lastPauseNs < longest) {
            lateWindow(g);
            windows++;
            waitOut(g);
        }
        assertTrue(windows <= 5, "longest pause after " + windows + " windows");
    }

    private static void lateWindow(GpuWorldgen.Governor g) {
        for (int i = 0; i < 64; i++) {
            g.answered(i % 8 == 0);
        }
    }

    private static void waitOut(GpuWorldgen.Governor g) throws InterruptedException {
        long until = System.nanoTime() + 2_000_000_000L;
        while (!g.asking()) {
            assertTrue(System.nanoTime() < until, "the pause ends");
            Thread.sleep(1);
        }
    }
}
