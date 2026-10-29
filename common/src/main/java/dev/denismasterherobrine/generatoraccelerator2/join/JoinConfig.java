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

    static {
        ConfigSpec.Builder b = new ConfigSpec.Builder();
        b.push("join");
        SPAWN_AREA_RADIUS = b.comment("Chunks around where you appear that must be finished before you enter the world, as a radius: 1 = 3x3,",
            "2 = 5x5, 3 = 7x7 (what vanilla waits for), 0 = only your own chunk. Never more than vanilla waits for. The other chunks",
            "keep loading after you enter, under vanilla's spawn ticket and then your own view.")
            .defineInRange("spawnAreaRadius", 1, 0, 3);
        DEDICATED_SERVERS = b.comment("Apply spawnAreaRadius to players joining a dedicated server too. They then enter while the chunks",
            "around them still generate.").define("dedicatedServers", false);
        PREFETCH_SPAWN_AREA = b.comment("While a new world looks for its spawn point (one chunk at a time, each generated completely first), start",
            "generating the area around the first candidate right away instead of after the search.").define("prefetchSpawnArea", true);
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

    public static int spawnAreaRadius() {
        try {
            return SPEC.isLoaded() ? SPAWN_AREA_RADIUS.get() : 1;
        } catch (RuntimeException e) {
            return 1;
        }
    }
}
