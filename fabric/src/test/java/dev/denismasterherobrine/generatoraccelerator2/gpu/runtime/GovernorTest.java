package dev.denismasterherobrine.generatoraccelerator2.gpu.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
