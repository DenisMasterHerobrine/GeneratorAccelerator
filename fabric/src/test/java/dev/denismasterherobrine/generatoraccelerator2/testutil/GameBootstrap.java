package dev.denismasterherobrine.generatoraccelerator2.testutil;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Bootstraps the game (registries, blocks, worldgen types) once before any test class runs, as NeoForge's unit test
 * environment did. Registered for every test through JUnit's extension auto-detection ({@code junit-platform.properties}).
 *
 * <p>JUnit loads this class itself. The game the tests use is Fabric Loader's copy (with GA2's mixins), loaded by the test
 * classes' loader, so the game is bootstrapped through that loader.
 */
public final class GameBootstrap implements BeforeAllCallback {
    private static volatile boolean done;

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        if (done) {
            return;
        }
        synchronized (GameBootstrap.class) {
            if (!done) {
                ClassLoader game = context.getRequiredTestClass().getClassLoader();
                Class.forName("net.minecraft.SharedConstants", true, game).getMethod("tryDetectVersion").invoke(null);
                Class.forName("net.minecraft.server.Bootstrap", true, game).getMethod("bootStrap").invoke(null);
                done = true;
            }
        }
    }
}
