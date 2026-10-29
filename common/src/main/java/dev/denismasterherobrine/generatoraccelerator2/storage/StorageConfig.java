package dev.denismasterherobrine.generatoraccelerator2.storage;

import dev.denismasterherobrine.generatoraccelerator2.config.ConfigSpec;

/**
 * Config for saving and loading chunks ({@code config/generatoraccelerator2/storage.toml}). Saved chunks hold vanilla's tags in
 * vanilla's format (their deflate stream is vanilla's bytes at {@code compressionLevel} 6), and loaded chunks are the same
 * objects vanilla builds, with every setting.
 */
public final class StorageConfig {
    private StorageConfig() {}

    public static final ConfigSpec SPEC;
    public static final ConfigSpec.BooleanValue FAST_PACK;
    public static final ConfigSpec.BooleanValue FAST_REGION_WRITE;
    public static final ConfigSpec.BooleanValue REGION_SCAN;
    public static final ConfigSpec.BooleanValue PRECOMPRESS;
    public static final ConfigSpec.BooleanValue SECTION_CODEC;
    public static final ConfigSpec.IntValue SYNC_DELAY_MS;
    public static final ConfigSpec.IntValue COMPRESSION_LEVEL;
    public static final ConfigSpec.BooleanValue PARALLEL_READ;

    static {
        ConfigSpec.Builder b = new ConfigSpec.Builder();
        b.push("storage");
        FAST_PACK = b.comment("Re-pack block and biome palettes for saving in one pass over each section, with vanilla's palette order",
            "and bit widths, instead of through a hash palette per entry.").define("fastPack", true);
        FAST_REGION_WRITE = b.comment("Write a chunk's NBT into a plain buffer and compress it with a reused compressor at compressionLevel (at 6 the same bytes as vanilla's)",
            "instead of through locked streams and a new compressor per chunk. Only for deflate region files (the default).").define("fastRegionWrite", true);
        REGION_SCAN = b.comment("Check a region for chunks saved by older versions (blending) in one task on the IO thread instead of 1024 round trips,",
            "each of which waits behind a chunk write. Matters on slow disks, where it holds up chunk generation.").define("regionScan", true);
        PRECOMPRESS = b.comment("Compress saved chunks on the worker pool as soon as they are handed to the region file's IO thread (the bytes the IO thread would write),",
            "so the one IO thread per storage only writes. Needs fastRegionWrite.").define("precompress", true);
        SECTION_CODEC = b.comment("Build the block state and biome tags of saved sections directly (same tags) instead of through the generic codecs.")
            .define("sectionCodec", true);
        COMPRESSION_LEVEL = b.comment("zlib level of saved chunks in deflate region files (the default format). The format stays vanilla's, so every",
            "Minecraft version and every world tool reads the chunks at any level, with or without GA2. 1 compresses about three times",
            "as fast as vanilla's 6 (1.7 to 2.6 ms of CPU per chunk at 6 in measured worlds) and the files come out about a fifth larger.",
            "6 writes vanilla's exact bytes. Needs fastRegionWrite.").defineInRange("compressionLevel", 1, 1, 9);
        SYNC_DELAY_MS = b.comment("Region files that vanilla would sync on every chunk write (singleplayer, and servers with sync-chunk-writes=true) are",
            "synced once per batch of writes instead, at most this many milliseconds after the batch's first write. The bytes on disk are the",
            "same. A crash loses at most the last batch, as vanilla's sync-chunk-writes=false loses its last writes. On a hard disk a synced",
            "write is a head move and a flush, twice per chunk, which held saving to about 50 chunks a second. 0 = vanilla's write-through.")
            .defineInRange("syncDelayMs", 200, 0, 60000);
        b.pop();
        b.push("loading");
        PARALLEL_READ = b.comment("Loading saved chunks: the region file's IO thread only reads a chunk's bytes, and the chunk is decompressed and",
            "parsed on the worker pool (same tag), so loads are not queued behind one thread. Chunk and entity storage.").define("parallelRead", true);
        b.pop();
        SPEC = b.build();
    }

    public static int syncDelayMs() {
        try {
            return !SPEC.isLoaded() ? 200 : SYNC_DELAY_MS.get();
        } catch (RuntimeException e) {
            return 200;
        }
    }

    public static int compressionLevel() {
        try {
            return !SPEC.isLoaded() ? 1 : COMPRESSION_LEVEL.get();
        } catch (RuntimeException e) {
            return 1;
        }
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

    public static boolean fastPack() {
        try {
            return !SPEC.isLoaded() || FAST_PACK.get();
        } catch (RuntimeException e) {
            return true;
        }
    }
}
