package dev.denismasterherobrine.generatoraccelerator2.join;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * Lets a player into the world once the chunks around where they appear are complete, and starts generating a new world's
 * spawn area during the spawn search.
 *
 * <p>A joining player waits in the configuration phase ({@code PrepareSpawnTask}) until every chunk within 3 of their chunk is
 * complete, 7x7 chunks. The player needs only the chunks right around them. Here the player joins once the chunks within
 * {@code spawnAreaRadius} are complete. For the default of 1 that is the 3x3 around the player. Vanilla's spawn ticket stays as
 * it is, so the rest of the 7x7 keeps loading while the client connects, and the player's own tickets load the view after that.
 * A singleplayer client therefore enters the world while the outer chunks still generate, instead of after them.
 *
 * <p>A new world's spawn search generates its candidate chunks one at a time, each completely, until one has a place to stand.
 * The area around the first candidate starts generating with that candidate, under a dedicated ticket that lapses 30 seconds
 * after the server starts ticking. The spawn nearly always ends up there. By then the first player's own tickets hold what
 * they need. Neither changes what generates. Vanilla's search chooses the spawn point from the same chunks.
 */
public final class JoinWait {
    private JoinWait() {}

    private static final Logger LOGGER = Ga2Log.get("Join");

    /** What vanilla waits for, as the radius of complete chunks around the player's chunk. */
    public static final int VANILLA_RADIUS = 3;

    /**
     * Holds the area around the first spawn candidate until the joining player's own tickets do. The lifespan counts server ticks,
     * which start after the search. Tickets compare their types by identity.
     */
    public static final TicketType JOIN = new TicketType(20 * 30, TicketType.FLAG_LOADING);

    private static volatile long searchStart;
    private static volatile long searchNanos = -1;
    private static volatile boolean logged;

    /** The radius of complete chunks around the joining player's chunk that {@code server} waits for. Never more than vanilla's. */
    public static int radius(MinecraftServer server) {
        if (!(server.isSingleplayer() || JoinConfig.dedicatedServers()) || !HookTargets.allowed(HookTargets.JOIN_SPAWN_WAIT)) {
            return VANILLA_RADIUS;
        }
        return Math.min(VANILLA_RADIUS, JoinConfig.spawnAreaRadius());
    }

    /** True when every chunk within {@code radius} of {@code center} is complete. */
    public static boolean ready(ServerLevel level, ChunkPos center, int radius) {
        ServerChunkCache chunks = level.getChunkSource();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (chunks.getChunkNow(center.x() + dx, center.z() + dz) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Called by the joining player's preparation each tick until its load is done. True once the smaller area is ready. Vanilla's
     * own load, {@code vanillaDone}, also lets the player in.
     */
    public static boolean playerAreaReady(ServerLevel level, Vec3 position, boolean vanillaDone) {
        if (vanillaDone) {
            return true;
        }
        int radius = radius(level.getServer());
        if (radius >= VANILLA_RADIUS || !ready(level, ChunkPos.containing(BlockPos.containing(position)), radius)) {
            return false;
        }
        if (!logged) {
            logged = true;
            int side = 2 * radius + 1;
            LOGGER.info("player joined once the {}x{} chunks around them were complete (vanilla 7x7) -> the rest keeps loading", side, side);
        }
        return true;
    }

    /**
     * Called when the spawn search ({@code Climate.Sampler.findSpawnPosition}) finds its first candidate. The area around the
     * candidate that the first player will wait for starts generating now. Meanwhile the search generates the candidate chunks
     * one at a time.
     */
    public static void spawnCandidate(ServerLevel level, BlockPos candidate) {
        searchStart = System.nanoTime();
        if (JoinConfig.prefetchSpawnArea()) {
            level.getChunkSource().addTicketWithRadius(JOIN, ChunkPos.containing(candidate), Math.max(1, radius(level.getServer())));
        }
    }

    public static void spawnFound() {
        if (searchStart != 0L) {
            searchNanos = System.nanoTime() - searchStart;
            searchStart = 0L;
            LOGGER.info("spawn search {} ms{}", searchNanos / 1_000_000L, JoinConfig.prefetchSpawnArea() ? ", spawn area generating since its first candidate" : "");
        }
    }
}
