package dev.denismasterherobrine.generatoraccelerator2.chunkload;

import dev.denismasterherobrine.generatoraccelerator2.config.ConfigSpec;
import dev.denismasterherobrine.generatoraccelerator2.dfc.Compat;

/**
 * Config for loading and sending chunks ({@code config/generatoraccelerator2/chunk-loading.toml}). Loaded chunks and the
 * chunk packets are the same with every setting. Only when and on which thread the work runs changes.
 */
public final class ChunkLoadConfig {
    private ChunkLoadConfig() {}

    public static final ConfigSpec SPEC;
    public static final ConfigSpec.IntValue PLAYER_TICKET_WINDOW;
    public static final ConfigSpec.BooleanValue POI_PREFETCH;
    public static final ConfigSpec.IntValue ENTITY_LOAD_BUDGET;
    public static final ConfigSpec.BooleanValue PARALLEL_CHUNK_PACKETS;
    public static final ConfigSpec.IntValue PACKET_THREADS;

    static {
        ConfigSpec.Builder b = new ConfigSpec.Builder();
        b.push("chunkLoading");
        PLAYER_TICKET_WINDOW = b.comment("How many chunks around players may load at the same time. Vanilla loads 4 at a time, nearest first, and starts the",
            "next one only when one of the 4 is fully loaded. Chunks still load nearest first. 4 = vanilla. Applies to dimensions loaded",
            "after the change. Ignored while C2ME runs the chunk system.").defineInRange("playerTicketWindow", 32, 1, 1024);
        POI_PREFETCH = b.comment("Read a saved chunk's points of interest (beds, workstations, portals) from disk together with the chunk. Vanilla",
            "reads them later on the server thread and waits for the disk there.").define("poiPrefetch", true);
        ENTITY_LOAD_BUDGET = b.comment("Milliseconds per tick the server thread may spend adding the entities of loaded chunks to the world. The rest wait",
            "for the next tick. Vanilla adds all of them in one tick. 0 = no limit, as vanilla.").defineInRange("entityLoadBudgetMs", 5, 0, 50);
        PARALLEL_CHUNK_PACKETS = b.comment("Encode the block and biome data of the chunks sent to a player in parallel, on threads of their own. The server",
            "thread still builds the rest of each packet. The packets are the same.").define("parallelChunkPackets", true);
        PACKET_THREADS = b.comment("Threads that encode chunk packets, besides the server thread. 0 = a quarter of the processor threads, 1 to 4.")
            .defineInRange("packetThreads", 0, 0, 16);
        b.pop();
        SPEC = b.build();
    }

    private static volatile Boolean otherChunkSystem;

    /** True when C2ME runs the chunk system. GA2's chunk loading mixins are then not applied. */
    public static boolean otherChunkSystem() {
        Boolean other = otherChunkSystem;
        if (other == null) {
            other = Compat.isLoaded("c2me");
            otherChunkSystem = other;
        }
        return other;
    }

    private static boolean get(ConfigSpec.BooleanValue value, boolean fallback) {
        try {
            return SPEC.isLoaded() ? value.get() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static int get(ConfigSpec.IntValue value, int fallback) {
        try {
            return SPEC.isLoaded() ? value.get() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public static int playerTicketWindow() { return get(PLAYER_TICKET_WINDOW, 32); }
    public static boolean poiPrefetch() { return get(POI_PREFETCH, true); }
    public static long entityLoadBudgetNanos() { return get(ENTITY_LOAD_BUDGET, 5) * 1_000_000L; }
    public static boolean parallelChunkPackets() { return get(PARALLEL_CHUNK_PACKETS, true); }

    public static int packetThreads() {
        int n = get(PACKET_THREADS, 0);
        return n > 0 ? n : Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 4));
    }
}
