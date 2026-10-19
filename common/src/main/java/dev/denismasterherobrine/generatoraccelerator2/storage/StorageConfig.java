package dev.denismasterherobrine.generatoraccelerator2.storage;

import dev.denismasterherobrine.generatoraccelerator2.config.ConfigSpec;

/**
 * Config for saving and loading chunks ({@code config/generatoraccelerator2/storage.toml}). Saved chunks are identical to
 * vanilla's, and loaded chunks are the same objects vanilla builds, with every setting.
 */
public final class StorageConfig {
    private StorageConfig() {}

    public static final ConfigSpec SPEC;
    public static final ConfigSpec.BooleanValue FAST_PACK;
    public static final ConfigSpec.BooleanValue FAST_REGION_WRITE;
    public static final ConfigSpec.BooleanValue REGION_SCAN;
    public static final ConfigSpec.BooleanValue PRECOMPRESS;
    public static final ConfigSpec.BooleanValue SECTION_CODEC;
    public static final ConfigSpec.BooleanValue SAVE_PREP;
    public static final ConfigSpec.BooleanValue PARALLEL_READ;
    public static final ConfigSpec.BooleanValue LOAD_PREP;

    static {
        ConfigSpec.Builder b = new ConfigSpec.Builder();
        b.push("storage");
        FAST_PACK = b.comment("Re-pack block and biome palettes for saving in one pass over each section, with vanilla's palette order",
            "and bit widths, instead of through a hash palette per entry.").define("fastPack", true);
        FAST_REGION_WRITE = b.comment("Write a chunk's NBT into a plain buffer and compress it with a reused compressor (same bytes in the region file)",
            "instead of through locked streams and a new compressor per chunk. Only for deflate region files (the default).").define("fastRegionWrite", true);
        REGION_SCAN = b.comment("Check a region for chunks saved by older versions (blending) in one task on the IO thread instead of 1024 round trips,",
            "each of which waits behind a chunk write. Matters on slow disks, where it holds up chunk generation.").define("regionScan", true);
        PRECOMPRESS = b.comment("Compress saved chunks on the worker pool as soon as they are handed to the region file's IO thread (same bytes),",
            "so the one IO thread per storage only writes. Needs fastRegionWrite.").define("precompress", true);
        SECTION_CODEC = b.comment("Build the block state and biome tags of saved sections directly (same tags) instead of through the generic codecs.")
            .define("sectionCodec", true);
        SAVE_PREP = b.comment("Encode an unloading chunk's sections on the worker pool before the server thread saves it; the save takes each tag",
            "when its section is unchanged since (same tags), so the server thread mostly assembles the chunk's tag. An unload waits",
            "at most ten ticks for its sections. Needs fastPack and sectionCodec.").define("savePrep", true);
        b.pop();
        b.push("loading");
        PARALLEL_READ = b.comment("Loading saved chunks: the region file's IO thread only reads a chunk's bytes, and the chunk is decompressed and",
            "parsed on the worker pool (same tag), so loads are not queued behind one thread. Chunk and entity storage.").define("parallelRead", true);
        LOAD_PREP = b.comment("Loading saved chunks: decode the sections' block and biome palettes, build the sections (block counts) and read the",
            "scheduled tick lists on the worker pool before the server thread reads the chunk; the server thread takes the results",
            "(the same objects it would build) when the tags are unchanged.").define("loadPrep", true);
        b.pop();
        SPEC = b.build();
    }

    public static boolean fastRegionWrite() {
        try {
            return !SPEC.isLoaded() || FAST_REGION_WRITE.get();
        } catch (RuntimeException e) {
            return true;
        }
    }

    public static boolean regionScan() {
        try {
            return !SPEC.isLoaded() || REGION_SCAN.get();
        } catch (RuntimeException e) {
            return true;
        }
    }

    public static boolean precompress() {
        try {
            return !SPEC.isLoaded() || PRECOMPRESS.get();
        } catch (RuntimeException e) {
            return true;
        }
    }

    public static boolean savePrep() {
        try {
            return !SPEC.isLoaded() || SAVE_PREP.get();
        } catch (RuntimeException e) {
            return true;
        }
    }

    public static boolean sectionCodec() {
        try {
            return !SPEC.isLoaded() || SECTION_CODEC.get();
        } catch (RuntimeException e) {
            return true;
        }
    }

    public static boolean parallelRead() {
        try {
            return !SPEC.isLoaded() || PARALLEL_READ.get();
        } catch (RuntimeException e) {
            return true;
        }
    }

    public static boolean loadPrep() {
        try {
            return !SPEC.isLoaded() || LOAD_PREP.get();
        } catch (RuntimeException e) {
            return true;
        }
    }

    public static boolean fastPack() {
        try {
            return !SPEC.isLoaded() || FAST_PACK.get();
        } catch (RuntimeException e) {
            return true;
        }
    }
}
