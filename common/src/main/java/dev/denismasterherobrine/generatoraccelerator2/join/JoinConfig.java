package dev.denismasterherobrine.generatoraccelerator2.join;

import dev.denismasterherobrine.generatoraccelerator2.config.ConfigSpec;

/**
 * Config for getting into a world sooner ({@code config/generatoraccelerator2/join.toml}). Nothing here changes what is
 * generated. It changes only how much of it a world waits for before players can join, and when chunks start loading.
 */
public final class JoinConfig {
    private JoinConfig() {}

    public static final ConfigSpec SPEC;
    public static final ConfigSpec.IntValue SPAWN_AREA_RADIUS;
    public static final ConfigSpec.BooleanValue DEDICATED_SERVERS;
    public static final ConfigSpec.BooleanValue PREFETCH_SPAWN_AREA;
    public static final ConfigSpec.BooleanValue PREFETCH_PLAYER_AREA;

    static {
        ConfigSpec.Builder b = new ConfigSpec.Builder();
        b.push("join");
        SPAWN_AREA_RADIUS = b.comment("Chunks around the world spawn that must be finished before you can join a singleplayer world, as a radius:",
            "2 = 5x5 chunks (the 3x3 around the spawn, where a new player appears, ready to play), 1 = 3x3 (only the spawn chunk",
            "ready to play), 3 = 7x7 (what vanilla waits for with the default spawnChunkRadius game rule), 0 = do not wait. Never",
            "more than vanilla waits for. The other spawn chunks keep loading after you join, as with a smaller spawnChunkRadius;",
            "the game rule itself stays as it is.")
            .defineInRange("spawnAreaRadius", 1, 0, 33);
        DEDICATED_SERVERS = b.comment("Apply spawnAreaRadius to dedicated servers too. They then report Done sooner, and a datapack's load function",
            "(it runs on the first tick) may find spawn chunks outside that area not loaded yet.").define("dedicatedServers", false);
        PREFETCH_SPAWN_AREA = b.comment("While a new world looks for its spawn point (one chunk at a time, each generated completely first), start",
            "generating the area around the first candidate right away instead of after the search.").define("prefetchSpawnArea", true);
        PREFETCH_PLAYER_AREA = b.comment("When a singleplayer world is opened, start loading the chunks around where you left off (in that dimension)",
            "while the world loads, so they are ready when you join instead of being requested after it.").define("prefetchPlayerArea", true);
        b.pop();
        SPEC = b.build();
    }

    private static boolean get(ConfigSpec.BooleanValue value, boolean fallback) {
        try {
            return SPEC.isLoaded() ? value.get() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public static boolean dedicatedServers() { return get(DEDICATED_SERVERS, false); }
    public static boolean prefetchSpawnArea() { return get(PREFETCH_SPAWN_AREA, true); }
    public static boolean prefetchPlayerArea() { return get(PREFETCH_PLAYER_AREA, true); }

    public static int spawnAreaRadius() {
        try {
            return SPEC.isLoaded() ? SPAWN_AREA_RADIUS.get() : 2;
        } catch (RuntimeException e) {
            return 2;
        }
    }
}
