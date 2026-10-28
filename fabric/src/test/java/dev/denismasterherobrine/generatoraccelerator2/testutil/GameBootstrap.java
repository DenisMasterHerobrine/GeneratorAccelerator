package dev.denismasterherobrine.generatoraccelerator2.testutil;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Bootstraps the game (registries, blocks, worldgen types) once before any test class runs. Registered for every test
 * through JUnit's extension auto-detection ({@code junit-platform.properties}).
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
                // the compat adapters the game registers at mod construction, before any hook is asked (Fabric API's air check in
                // section writes is the one the test runtime loads). A hook asked earlier would cache its answer without them.
                Class.forName("dev.denismasterherobrine.generatoraccelerator2.compat.CompatModules", true, game).getMethod("init").invoke(null);
                done = true;
            }
        }
    }
}
