package dev.denismasterherobrine.generatoraccelerator2.platform.fabric;

import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry;
import net.minecraft.world.level.material.Fluid;

/**
 * Whether Fabric API draws a fluid the game's own way. Fabric API sends every fluid render through the handler registered for the
 * fluid. Its built-in water and lava handlers hand the work back to the game's renderer with the game's sprites and colours. A mod
 * can register its own handler at any time, so this is asked on every render. Loaded only where Fabric API's fluid module is.
 */
final class FabricWaterRendering {
    private FabricWaterRendering() {}

    /** The package of Fabric API's built-in handlers. A handler a mod registers, even Fabric's {@code SimpleFluidRenderHandler}, is outside it. */
    private static final String BUILT_IN = "net.fabricmc.fabric.impl.";

    static boolean builtIn(Fluid fluid) {
        FluidRenderHandler handler = FluidRenderHandlerRegistry.INSTANCE.get(fluid);
        return handler == null || handler.getClass().getName().startsWith(BUILT_IN);
    }
}
