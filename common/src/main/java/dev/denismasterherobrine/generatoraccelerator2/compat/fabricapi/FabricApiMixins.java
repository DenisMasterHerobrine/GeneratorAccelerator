package dev.denismasterherobrine.generatoraccelerator2.compat.fabricapi;

import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
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

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.NOISE_SECTION_WRITES, SECTION_AIR, Set.of("modifyAirCheck"),
            "Fabric API's air check still applies. GA2 counts blocks in chunk sections with the same check."));

    /** Nothing to set up. GA2's section writes count blocks with the same test. */
    public static void register() {
        for (KnownMixins.Entry e : ENTRIES) {
            CompatAdapters.register(e.hook(), e.mixin(), () -> true);
        }
    }
}
