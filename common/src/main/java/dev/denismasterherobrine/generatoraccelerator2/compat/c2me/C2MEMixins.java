package dev.denismasterherobrine.generatoraccelerator2.compat.c2me;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.Notices;
import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/**
 * C2ME mixins that GA2 handles ({@link KnownMixins}). They are chunk-system changes to hooked methods. GA2's hooks compose with them and stay on. C2ME mixins on code that GA2
 * replaces are cancelled instead ({@code compat.Takeover}). Contains no Minecraft types, because the mixin plugin reads it.
 */
public final class C2MEMixins {
    private C2MEMixins() {}

    public static final String MOD_ID = "c2me";

    /**
     * C2ME's chunk-system executor for the noise and biome steps ({@code @ModifyArg} of the executor passed to
     * {@code supplyAsync}). The GPU hooks answer the chunks they take at the head of the method. Other chunks run vanilla's
     * body on C2ME's executor.
     */
    public static final String GENERATOR_EXECUTOR = "com.ishland.c2me.rewrites.chunksystem.mixin.MixinNoiseChunkGenerator";
    /**
     * C2ME's async serialization wraps the region's {@code onBlockStateChange} ({@code @WrapOperation}). The wrapper defers
     * the call until the chunk's initial main-thread work is done. GA2's POI skip leaves out only calls that change no POI.
     * All other calls reach C2ME's wrapper.
     */
    public static final String REGION_NOTIFY = "com.ishland.c2me.rewrites.chunksystem.mixin.async_serialization.MixinChunkRegion";
    /**
     * C2ME's async serialization runs the chunk IO worker's mailbox on C2ME's IO executor ({@code @Redirect} of
     * {@code Util.ioPool} in the constructor). The mailbox still runs one task at a time in order, so GA2's compression
     * ahead of the write is unaffected. The mixin also overwrites {@code createOldDataForRegion}. GA2's region scan answers
     * at the head of that method, so C2ME's body only runs with the region scan off.
     */
    public static final String IO_WORKER = "com.ishland.c2me.rewrites.chunksystem.mixin.async_serialization.MixinStorageIoWorker";

    private static final Set<String> EXECUTOR_HANDLERS = Set.of("redirectPopulateNoiseExecutor", "redirectBiomeExecutor");
    private static final String IO_EXECUTOR_HANDLER = "redirectIoWorkerExecutor";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.GPU_NOISE, GENERATOR_EXECUTOR, EXECUTOR_HANDLERS,
            "C2ME's threads for terrain noise still work. Chunks made on the GPU skip them."),
        new KnownMixins.Entry(MOD_ID, HookTargets.GPU_BIOMES, GENERATOR_EXECUTOR, EXECUTOR_HANDLERS,
            "C2ME's threads for biome placement still work. Chunks made on the GPU skip them."),
        new KnownMixins.Entry(MOD_ID, HookTargets.STATE_POI_SKIP, REGION_NOTIFY, Set.of("waitForFutureBeforeNotifyChanges"),
            "C2ME's delayed point-of-interest updates still work. GA2 only skips block changes that cannot affect points of interest."),
        new KnownMixins.Entry(MOD_ID, HookTargets.STORAGE_PRECOMPRESS, IO_WORKER, Set.of(IO_EXECUTOR_HANDLER),
            "C2ME's threads for saving chunks still work. GA2 compresses the chunks before they get there."),
        // the overwritten method keeps its runtime name: Mojang's in development, intermediary on Fabric
        new KnownMixins.Entry(MOD_ID, HookTargets.STORAGE_OLD_CHUNK_SCAN, IO_WORKER,
            Set.of(IO_EXECUTOR_HANDLER, KnownMixins.overwrite("createOldDataForRegion"), KnownMixins.overwrite("method_42333")),
            "C2ME's threads for saving chunks still work. GA2's faster scan finds old chunks for terrain blending."));

    /** Shown once to players with C2ME installed, and logged on every start. */
    public static final String NOTICE = "It seems that C2ME and Generator Accelerator 2 are installed in the modpack. While this shouldn't cause crashes and/or drastic issues, "
        + "something weird may happen. Proceed with caution. Generator Accelerator 2 will disable some parts of C2ME to not break or crash anything.";

    public static void register() {
        Ga2Log.get("Compat").warn(NOTICE);
        Notices.post(NOTICE);
        for (KnownMixins.Entry e : ENTRIES) {
            CompatAdapters.register(e.hook(), e.mixin(), () -> true);
        }
    }
}
