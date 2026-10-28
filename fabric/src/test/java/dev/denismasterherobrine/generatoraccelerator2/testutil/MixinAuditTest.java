package dev.denismasterherobrine.generatoraccelerator2.testutil;

import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.MixinEnvironment;

/**
 * Applies every mixin to its target class now, client classes included, rather than when the game first loads the class. A
 * mixin whose target, method or injection point the game does not have fails here.
 */
public class MixinAuditTest {
    @Test
    void everyMixinApplies() {
        MixinEnvironment.getCurrentEnvironment().audit();
    }
}
