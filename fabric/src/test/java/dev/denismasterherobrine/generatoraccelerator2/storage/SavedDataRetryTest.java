package dev.denismasterherobrine.generatoraccelerator2.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ConcurrentModificationException;
import org.junit.jupiter.api.Test;

/** {@link SavedDataRetry}: an encoding that races another thread's addition is started over. */
public class SavedDataRetryTest {
    @Test
    void aRacingEncodingStartsOver() {
        int[] calls = new int[1];
        String result = SavedDataRetry.encode("data", () -> {
            if (++calls[0] <= 3) {
                throw new ConcurrentModificationException();
            }
            return "whole";
        });
        assertEquals("whole", result);
        assertEquals(4, calls[0]);
    }

    @Test
    void anEncodingThatKeepsFailingStillThrows() {
        int[] calls = new int[1];
        assertThrows(ConcurrentModificationException.class, () -> SavedDataRetry.encode("data", () -> {
            calls[0]++;
            throw new ConcurrentModificationException();
        }));
        assertEquals(SavedDataRetry.ATTEMPTS, calls[0]);
    }
}
