package dev.denismasterherobrine.generatoraccelerator2.join;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import com.mojang.serialization.Dynamic;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import java.util.Comparator;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.slf4j.Logger;

/**
 * Lets players into a world once the chunks they appear in are ready, and starts loading those chunks as early as the
 * world allows.
 *
 * <p>Before anyone can join, vanilla's server makes every chunk within {@code spawnChunkRadius} of the world spawn tick.
 * That is 5x5 chunks with the default game rule. It requires the 7x7 around them complete, and the 11x11 around those
 * generated up to their carvers. A joining player needs only one of those chunks ready: the one the player appears in.
 * A new player appears within 10 blocks of the spawn. The server sends that chunk once it ticks. Here the server waits
 * until the chunks within {@code spawnAreaRadius} of the spawn chunk are complete and those within one less tick. For
 * the default of 1, only the spawn chunk itself ticks. The server then lets players in. The spawn ticket is
 * vanilla's, so the rest of the spawn chunks keep loading while the client connects, as with a smaller game rule. A
 * singleplayer client connects to the server that just became ready. That time therefore overlaps the rest of the
 * loading instead of following it.
 *
 * <p>Two areas start loading earlier, under a dedicated ticket that lapses 30 seconds after the server starts ticking.
 * By then the players' own tickets hold what they need.
 * <ul>
 * <li>A new world's spawn search generates its candidate chunks one at a time, each completely, until one has a place
 * to stand. The area around the first candidate starts generating with that candidate, instead of after the search.
 * The spawn nearly always ends up there.</li>
 * <li>A singleplayer player who left off away from the spawn appears there. Vanilla requests those chunks after the
 * player joined. Here they start loading with the spawn area.</li>
 * </ul>
 * Neither changes what generates. Vanilla's search chooses the spawn point from the same chunks.
 */
public final class JoinWait {
    private JoinWait() {}

    private static final Logger LOGGER = Ga2Log.get("Join");

    /** Holds the prefetched areas until the joining player's own tickets do. The lifespan counts server ticks, which start after the wait. */
    public static final TicketType<ChunkPos> JOIN = TicketType.create("generatoraccelerator2:join", Comparator.comparingLong(ChunkPos::toLong), 20 * 30);

    private static volatile long searchStart;
    private static volatile long searchNanos = -1;
    private static volatile long prepareStart;
    @Nullable
    private static volatile String playerArea;

    /** What vanilla waits for, as the radius of complete chunks around the spawn chunk. Equals the spawn ticket's distance. */
    public static int vanillaRadius(int spawnChunkRadius) {
        return spawnChunkRadius > 0 ? spawnChunkRadius + 1 : 0;
    }

    /** The radius of complete chunks around the spawn chunk that {@code server} waits for. Uses {@code spawnAreaRadius} where it applies and is smaller. */
    public static int radius(MinecraftServer server) {
        return radius(server.isSingleplayer(), server.getGameRules().getInt(GameRules.RULE_SPAWN_CHUNK_RADIUS));
    }

    public static int radius(boolean singleplayer, int spawnChunkRadius) {
        int vanilla = vanillaRadius(spawnChunkRadius);
        if (!(singleplayer || JoinConfig.dedicatedServers()) || !HookTargets.allowed(HookTargets.JOIN_SPAWN_WAIT)) {
            return vanilla;
        }
        return Math.min(vanilla, JoinConfig.spawnAreaRadius());
    }

    /**
     * True when every chunk within {@code radius - 1} of {@code center} ticks, so a player there can be sent their chunk.
     * That requires the chunks within {@code radius} complete. Always true for radius 0.
     */
    public static boolean ready(ServerLevel level, ChunkPos center, int radius) {
        ChunkMap map = level.getChunkSource().chunkMap;
        for (int dx = 1 - radius; dx < radius; dx++) {
            for (int dz = 1 - radius; dz < radius; dz++) {
                ChunkHolder holder = map.getVisibleChunkIfPresent(ChunkPos.asLong(center.x + dx, center.z + dz));
                if (holder == null || holder.getTickingChunk() == null) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Called when the spawn search ({@code Climate.Sampler.findSpawnPosition}) finds its first candidate. The area around the
     * candidate that the server will wait for starts generating now. Meanwhile the search generates the candidate chunks
     * one at a time.
     */
    public static void spawnCandidate(ServerLevel level, BlockPos candidate) {
        searchStart = System.nanoTime();
        if (JoinConfig.prefetchSpawnArea()) {
            ChunkPos chunk = new ChunkPos(candidate);
            level.getChunkSource().addRegionTicket(JOIN, chunk, Math.max(1, radius(level.getServer())), chunk);
        }
    }

    public static void spawnFound() {
        if (searchStart != 0L) {
            searchNanos = System.nanoTime() - searchStart;
            searchStart = 0L;
        }
    }

    /**
     * Called when the levels exist and the server is about to wait for the spawn area. A singleplayer player who has played
     * the world appears where they left off, as stored in level.dat. The area the player needs there starts loading now,
     * and the server does not wait for it. That area is as large as the spawn area waited for. It is at least the 3x3 that
     * lets the player's chunk tick.
     */
    public static void preparing(MinecraftServer server) {
        prepareStart = System.nanoTime();
        playerArea = null;
        if (!server.isSingleplayer() || !JoinConfig.prefetchPlayerArea()) {
            return;
        }
        CompoundTag player = server.getWorldData().getLoadedPlayerTag();
        if (player == null) {
            return;
        }
        ListTag pos = player.getList("Pos", Tag.TAG_DOUBLE);
        if (pos.size() != 3) {
            return;
        }
        ResourceKey<Level> dimension = player.contains("Dimension")
            ? DimensionType.parseLegacy(new Dynamic<>(NbtOps.INSTANCE, player.get("Dimension"))).result().orElse(Level.OVERWORLD) : Level.OVERWORLD;
        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            // Vanilla puts a player whose dimension is gone into the overworld.
            level = server.overworld();
        }
        ChunkPos chunk = new ChunkPos(BlockPos.containing(pos.getDouble(0), pos.getDouble(1), pos.getDouble(2)));
        int distance = Math.max(1, radius(server));
        int spawnTicket = vanillaRadius(server.getGameRules().getInt(GameRules.RULE_SPAWN_CHUNK_RADIUS));
        if (level == server.overworld() && chunk.getChessboardDistance(new ChunkPos(level.getSharedSpawnPos())) + distance <= spawnTicket) {
            // Inside the spawn area, which loads anyway.
            return;
        }
        level.getChunkSource().addRegionTicket(JOIN, chunk, distance, chunk);
        playerArea = level.dimension().location() + " " + chunk.x + ", " + chunk.z;
    }

    /** The server stops waiting and lets players in. */
    public static void prepared(MinecraftServer server) {
        int radius = radius(server);
        int vanilla = vanillaRadius(server.getGameRules().getInt(GameRules.RULE_SPAWN_CHUNK_RADIUS));
        long search = searchNanos;
        searchNanos = -1;
        if (radius == vanilla && search < 0 && playerArea == null) {
            return;
        }
        StringBuilder msg = new StringBuilder();
        int side = 2 * radius + 1;
        int vanillaSide = 2 * vanilla + 1;
        msg.append(radius == 0 ? "spawn area skipped" : "spawn area " + side + "x" + side + " ready").append(radius == vanilla ? "" : " (vanilla " + vanillaSide + "x" + vanillaSide + ")")
            .append(" in ").append((System.nanoTime() - prepareStart) / 1_000_000L).append(" ms");
        if (search >= 0) {
            msg.append("; spawn search ").append(search / 1_000_000L).append(" ms");
        }
        if (playerArea != null) {
            msg.append("; player area ").append(playerArea).append(" loading since world load");
        }
        LOGGER.info(msg.toString());
    }
}
