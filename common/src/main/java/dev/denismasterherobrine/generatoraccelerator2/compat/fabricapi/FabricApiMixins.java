package dev.denismasterherobrine.generatoraccelerator2.compat.fabricapi;

import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/**
 * Fabric API mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it.
 * Fabric API is present in every Fabric game. Its changes to vanilla therefore count as the platform's, as Forge's
 * patches do on Forge.
 */
public final class FabricApiMixins {
    private FabricApiMixins() {}

    public static final String MOD_ID = "fabric-data-attachment-api-v1";
    /** The module's id on Forge (Forgified Fabric API), where the Fabric id is only a provided alias. */
    public static final String FORGE_MOD_ID = "fabric_data_attachment_api_v1";

    /**
     * Reads a loaded chunk's attachments from Fabric's own compound of the root tag, right after {@code ChunkSerializer.read}
     * makes the chunk, and writes them back at the end of {@code write}. No section, palette or tick list tag is touched.
     */
    public static final String CHUNK_ATTACHMENTS = "net.fabricmc.fabric.mixin.attachment.ChunkSerializerMixin";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.STORAGE_LOAD_PREP_UNCHANGED, CHUNK_ATTACHMENTS,
            Set.of("readWorldChunkAttachments", "readProtoChunkAttachments", "writeChunkAttachments"),
            "Fabric API's chunk attachments still load and save. They do not touch the block or tick data GA2 prepares."));

    /** Nothing to set up. The prepared parts are used as they are. */
    public static void register() {
        for (KnownMixins.Entry e : ENTRIES) {
            CompatAdapters.register(e.hook(), e.mixin(), () -> true);
        }
    }
}
