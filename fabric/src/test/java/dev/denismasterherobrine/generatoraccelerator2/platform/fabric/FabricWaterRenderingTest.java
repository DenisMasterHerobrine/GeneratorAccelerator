package dev.denismasterherobrine.generatoraccelerator2.platform.fabric;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.Test;

/** Water counts as drawn the game's way under Fabric API's built-in handler and stops counting once a mod registers its own. */
class FabricWaterRenderingTest {
    @Test
    void modHandlerTurnsTheSkipOff() {
        FluidRenderHandlerRegistry registry;
        try {
            registry = FluidRenderHandlerRegistry.INSTANCE;
        } catch (LinkageError e) {
            registry = null;
        }
        assumeTrue(registry != null, "no client classes in this environment");
        FluidRenderHandler original = registry.get(Fluids.WATER);
        assertTrue(FabricWaterRendering.builtIn(Fluids.WATER), "built-in handler: " + original);
        FluidRenderHandler own = new FluidRenderHandler() {
            @Override
            public TextureAtlasSprite[] getFluidSprites(BlockAndTintGetter view, BlockPos pos, FluidState state) {
                return null;
            }
        };
        try {
            registry.register(Fluids.WATER, own);
            assertFalse(FabricWaterRendering.builtIn(Fluids.WATER));
            assertTrue(FabricWaterRendering.builtIn(Fluids.LAVA), "only water's handler changed");
        } finally {
            registry.register(Fluids.WATER, original);
        }
        assertTrue(FabricWaterRendering.builtIn(Fluids.WATER));
    }
}
