package dev.denismasterherobrine.generatoraccelerator2.features.tree;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@link LeafSets} against {@code java.util.HashSet<BlockPos>}: every element taken in the same order, through resizes and tree bins. */
public class LeafSetsTest {
    @Test
    void takesAsHashSet() {
        assertNull(LeafSets.check(20261010L, 48, 600, null));
        assertTrue(LeafSets.VERIFIED, "the start-up check failed");
        int[] handedOver = new int[1];
        assertNull(LeafSets.check(7L, 3000, 1500, handedOver));
        System.out.println("LeafSetsTest: " + handedOver[0] + " sets reached a tree bin and went to a real HashSet");
        assertTrue(handedOver[0] > 20, "too few tree bins exercised: " + handedOver[0]);
    }
}
