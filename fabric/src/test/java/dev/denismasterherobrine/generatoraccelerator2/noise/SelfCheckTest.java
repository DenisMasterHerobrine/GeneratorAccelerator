package dev.denismasterherobrine.generatoraccelerator2.noise;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The mixin-replaced kernel (which every other suite treats as "vanilla") must itself match the
 * line-by-line vanilla port in {@link ReferenceNoise}. This is the root of the exactness chain.
 */
public class SelfCheckTest {
    @Test
    void fusedKernelMatchesVanillaReference() {
        assertTrue(SelfCheck.run(LoggerFactory.getLogger("SelfCheckTest")), "fused kernel differs from the vanilla reference port");
    }
}
