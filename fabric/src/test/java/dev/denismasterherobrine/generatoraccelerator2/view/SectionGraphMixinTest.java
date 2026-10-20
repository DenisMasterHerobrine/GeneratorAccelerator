package dev.denismasterherobrine.generatoraccelerator2.view;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The section occlusion graph keeps the sections at the view's edge when a rebuilt graph is shown. */
class SectionGraphMixinTest {
    private static Optional<Class<?>> graph() {
        try {
            return Optional.of(Class.forName("net.minecraft.client.renderer.SectionOcclusionGraph", true, SectionGraphMixinTest.class.getClassLoader()));
        } catch (ClassNotFoundException | LinkageError e) {
            return Optional.empty();
        }
    }

    private static Method method(Class<?> c, String suffix) {
        return Arrays.stream(c.getDeclaredMethods()).filter(m -> m.getName().endsWith(suffix)).findFirst().orElseThrow();
    }

    @Test
    void mixinApplied() {
        Optional<Class<?>> c = graph();
        assumeTrue(c.isPresent(), "no client classes in this environment");
        for (String name : new String[] { "ga2$keepEdge", "ga2$beforeUpdate", "ga2$beforeFrustum", "ga2$rebuild", "ga2$reset" }) {
            assertTrue(Arrays.stream(c.get().getDeclaredMethods()).anyMatch(m -> m.getName().endsWith(name)), name);
        }
    }

    /** Distance 4 around chunk 0, 0 for the rebuild and around chunk 3, 0 for the camera now. */
    @Test
    void edgeIsBeyondTheRebuildAndWithinTheCamera() throws ReflectiveOperationException {
        Optional<Class<?>> c = graph();
        assumeTrue(c.isPresent(), "no client classes in this environment");
        Method edge = method(c.get(), "ga2$edge");
        edge.setAccessible(true);
        // ahead: beyond the rebuild's distance, within the camera's
        assertTrue((boolean) edge.invoke(null, 0, 0, 3, 0, 4, 6, 0));
        // decided by the rebuild
        assertFalse((boolean) edge.invoke(null, 0, 0, 3, 0, 4, 2, 0));
        // behind the camera, out of view
        assertFalse((boolean) edge.invoke(null, 0, 0, 3, 0, 4, -3, 0));
        // beyond both
        assertFalse((boolean) edge.invoke(null, 0, 0, 3, 0, 4, 9, 0));
    }
}
