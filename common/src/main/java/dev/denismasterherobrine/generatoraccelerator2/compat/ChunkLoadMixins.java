package dev.denismasterherobrine.generatoraccelerator2.compat;

import java.util.List;
import java.util.Set;

/**
 * Other mods' changes to {@code ChunkSerializer.read} that leave the section, palette and tick list tags of a loaded chunk
 * alone. Results prepared from those tags on the worker pool ({@code storage.LoadPrep}) therefore still match what
 * {@code read} would make ({@link HookTargets#STORAGE_LOAD_PREP_UNCHANGED}). Each mixin was checked in its bytecode:
 * <ul>
 * <li>Tectonic ({@code ChunkDataMixin}, at the head of {@code read}): applies in the overworld, to chunks of some statuses
 * saved before Tectonic's blending version. It adds a {@code blending_data} compound to the chunk's root tag and removes
 * {@code Heightmaps} and {@code isLightOn} from the root tag. {@code read} then primes the heightmaps itself, as it does
 * without GA2. Tectonic only reads the section list. Tectonic's other handler changes {@code write}.</li>
 * <li>Confluence ({@code ChunkSerializerMixin}, at each {@code PoiManager.checkConsistencyWithBlocks} call): reads
 * Confluence's own {@code backup_biome} entry of the section tag. It sets that biome on the section object that
 * {@code read} just made. A prepared section is that same object.</li>
 * <li>CorgiLib and Oh The Trees You'll Grow ({@code MixinChunkSerializer}, at the return of {@code read}): each reads its
 * own compounds of the root tag into the chunk's scheduled random ticks.</li>
 * <li>Architectury ({@code MixinChunkSerializer}, at the head of {@code read} and on the argument of the second event post):
 * keeps the level in a thread local and attaches the level to NeoForge's {@code ChunkDataEvent.Load}. No tag is touched.</li>
 * <li>Leaves Be Gone ({@code ChunkSerializerMixin}, on the chunk's first store in {@code read}): reads its own list of the root tag
 * into the chunk's random block ticks. Leaves Be Gone's other handler adds that list in {@code write}.</li>
 * <li>ScalableLux (Starlight's {@code ChunkSerializerMixin}, at the return of {@code read}): reads the light arrays of the section
 * tags into ScalableLux's own light storage, without changing any tag.</li>
 * </ul>
 * Contains no Minecraft types, because the mixin plugin loads it.
 */
public final class ChunkLoadMixins {
    private ChunkLoadMixins() {}

    public static final String TECTONIC_ID = "tectonic";
    public static final String TECTONIC = "dev.worldgen.tectonic.mixin.ChunkDataMixin";
    public static final String CONFLUENCE_ID = "confluence";
    public static final String CONFLUENCE = "org.confluence.mod.mixin.chunk.ChunkSerializerMixin";
    public static final String CORGILIB_ID = "corgilib";
    public static final String CORGILIB = "corgitaco.corgilib.mixin.chunk.MixinChunkSerializer";
    public static final String OTTYG_ID = "ohthetreesyoullgrow";
    public static final String OTTYG = "dev.corgitaco.ohthetreesyoullgrow.mixin.chunk.MixinChunkSerializer";
    public static final String ARCHITECTURY_ID = "architectury";
    public static final String ARCHITECTURY = "dev.architectury.mixin.forge.neoforge.MixinChunkSerializer";
    public static final String LEAVES_BE_GONE_ID = "leavesbegone";
    public static final String LEAVES_BE_GONE = "fuzs.leavesbegone.mixin.ChunkSerializerMixin";
    public static final String SCALABLELUX_ID = "scalablelux";
    public static final String SCALABLELUX = "ca.spottedleaf.starlight.mixin.common.world.ChunkSerializerMixin";

    private static final String WHAT = "This mod's chunk save and load changes still work. "
        + "They do not touch the block or tick data GA2 prepares, so faster chunk loading stays on.";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(TECTONIC_ID, HookTargets.STORAGE_LOAD_PREP_UNCHANGED, TECTONIC, Set.of("tectonic$read", "tectonic$write"), WHAT),
        new KnownMixins.Entry(CONFLUENCE_ID, HookTargets.STORAGE_LOAD_PREP_UNCHANGED, CONFLUENCE, Set.of("read", "write", "lambda$read$0"), WHAT),
        new KnownMixins.Entry(CORGILIB_ID, HookTargets.STORAGE_LOAD_PREP_UNCHANGED, CORGILIB, Set.of("readScheduledRandomTicks", "writeScheduledRandomTicks"), WHAT),
        new KnownMixins.Entry(OTTYG_ID, HookTargets.STORAGE_LOAD_PREP_UNCHANGED, OTTYG, Set.of("readScheduledRandomTicks", "writeScheduledRandomTicks"), WHAT),
        new KnownMixins.Entry(ARCHITECTURY_ID, HookTargets.STORAGE_LOAD_PREP_UNCHANGED, ARCHITECTURY, Set.of("read", "modifyProtoChunkLevel"), WHAT),
        new KnownMixins.Entry(LEAVES_BE_GONE_ID, HookTargets.STORAGE_LOAD_PREP_UNCHANGED, LEAVES_BE_GONE, Set.of("leavesbegone$read", "leavesbegone$write"), WHAT),
        new KnownMixins.Entry(SCALABLELUX_ID, HookTargets.STORAGE_LOAD_PREP_UNCHANGED, SCALABLELUX, Set.of("saveLightHook", "loadLightHook"), WHAT));

    /** None of these mixins needs setup. The prepared parts are used as they are. */
    public static void register() {
        for (KnownMixins.Entry e : ENTRIES) {
            if (dev.denismasterherobrine.generatoraccelerator2.dfc.Compat.isLoaded(e.modId())) {
                CompatAdapters.register(e.hook(), e.mixin(), () -> true);
            }
        }
    }
}
