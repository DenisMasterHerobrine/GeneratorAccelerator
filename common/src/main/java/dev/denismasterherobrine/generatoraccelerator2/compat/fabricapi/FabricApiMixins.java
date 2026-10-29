package dev.denismasterherobrine.generatoraccelerator2.compat.fabricapi;

import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import dev.denismasterherobrine.generatoraccelerator2.platform.Platform;
import java.util.List;
import java.util.Set;

/**
 * Fabric API mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it.
 * Fabric API is present in every Fabric game. Its changes to vanilla therefore count as the platform's, as NeoForge's
 * patches do on NeoForge.
 */
public final class FabricApiMixins {
    private FabricApiMixins() {}

    public static final String MOD_ID = "fabric-block-api-v1";

    /**
     * Replaces the air test in the block count of {@code LevelChunkSection.setBlockState} with a test for the three air
     * blocks. On Fabric, GA2's section writes count blocks with the same test ({@code platform.PlatformHooks.isEmpty}).
     */
    public static final String SECTION_AIR = "net.fabricmc.fabric.mixin.block.ChunkSectionMixin";

    public static final String ATTACHMENT_MOD_ID = "fabric-data-attachment-api-v1";
    /**
     * Reads a loaded chunk's attachments from Fabric's own compound of the root tag, right after {@code ChunkSerializer.read}
     * makes the chunk, and writes them back at the end of {@code write}. No section, palette or tick list tag is touched.
     */
    public static final String CHUNK_ATTACHMENTS = "net.fabricmc.fabric.mixin.attachment.ChunkSerializerMixin";

    public static final String FLUIDS_MOD_ID = "fabric-rendering-fluids-v1";
    /**
     * Sends every fluid render through the handler registered for the fluid. The built-in water handler draws the game's way, with
     * the game's face checks. GA2 leaves out water with water on all six sides only while water has that handler
     * ({@code platform.PlatformHooks.vanillaFluidRendering}, asked on every render).
     */
    public static final String FLUID_RENDERER = "net.fabricmc.fabric.mixin.client.rendering.fluid.FluidRendererMixin";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(FLUIDS_MOD_ID, HookTargets.FLUID_HIDDEN_WATER, FLUID_RENDERER,
            Set.of("onResourceReloadReturn", "onHeadRender", "modLavaCheck", "modSpriteArray", "modTintColor",
                "modWaterOverlaySprite", "getOverlayBlock", "modSideSpriteForOverlay"),
            "Fabric API still draws water. GA2 leaves out only water hidden by water on all sides, and only while no mod draws water its own way."),
        new KnownMixins.Entry(MOD_ID, HookTargets.NOISE_SECTION_WRITES, SECTION_AIR, Set.of("modifyAirCheck"),
            "Fabric API's air check still applies. GA2 counts blocks in chunk sections with the same check."),
        // GA2's body for the method counts with the same test, so it takes the method's place with Fabric API's change in it
        new KnownMixins.Entry(MOD_ID, HookTargets.SECTION_WRITE, SECTION_AIR, Set.of("modifyAirCheck"),
            "Fabric API's air check still applies. GA2's block writes count blocks with the same check."),
        new KnownMixins.Entry(ATTACHMENT_MOD_ID, HookTargets.STORAGE_LOAD_PREP_UNCHANGED, CHUNK_ATTACHMENTS,
            Set.of("readWorldChunkAttachments", "readProtoChunkAttachments", "writeChunkAttachments"),
            "Fabric API's chunk attachments still load and save. They do not touch the block or tick data GA2 prepares."));

    /** Nothing to set up. GA2's section writes count blocks with the same test. */
    public static void register() {
        for (KnownMixins.Entry e : ENTRIES) {
            if (e.mixin().equals(FLUID_RENDERER) && Platform.isNeoForge()) {
                continue; // only Fabric's platform hooks check the water handler. With Fabric API on NeoForge (Connector) the hook stays off
            }
            CompatAdapters.register(e.hook(), e.mixin(), () -> true);
        }
    }
}
