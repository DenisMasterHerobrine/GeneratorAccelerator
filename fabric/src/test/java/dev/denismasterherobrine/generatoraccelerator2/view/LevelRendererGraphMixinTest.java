package dev.denismasterherobrine.generatoraccelerator2.view;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The visibility graph's full rebuilds run on their own thread, from the camera of the moment they were scheduled. */
class LevelRendererGraphMixinTest {
    private static Optional<Class<?>> renderer() {
        try {
            return Optional.of(Class.forName("net.minecraft.client.renderer.LevelRenderer", false, LevelRendererGraphMixinTest.class.getClassLoader()));
        } catch (ClassNotFoundException | LinkageError e) {
            return Optional.empty();
        }
    }

    @Test
    void mixinApplied() {
        Optional<Class<?>> c = renderer();
        assumeTrue(c.isPresent(), "no client classes in this environment");
        for (String name : new String[] { "ga2$graphExecutor", "ga2$rebuild", "ga2$scheduledPosition", "ga2$scheduledBlock" }) {
            assertTrue(Arrays.stream(c.get().getDeclaredMethods()).anyMatch(m -> m.getName().endsWith(name)), name);
        }
    }
}
