package dev.denismasterherobrine.generatoraccelerator2.compat.chunky;

import dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload.ChunkMapUpdatingInvoker;
import java.util.Comparator;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/** GA2's tickets on the chunks {@link ChunkyHolds} keeps, in one dimension. */
public final class ChunkyHoldTickets implements ChunkyHolds.Tickets {
    /** Without a timeout. A kept chunk's ticket goes when the chunk is let go. */
    static final TicketType<ChunkPos> TICKET = TicketType.create("generatoraccelerator2:chunky_hold", Comparator.comparingLong(ChunkPos::toLong));

    private final DistanceManager distanceManager;
    private final ChunkMap chunkMap;

    public ChunkyHoldTickets(DistanceManager distanceManager, ChunkMap chunkMap) {
        this.distanceManager = distanceManager;
        this.chunkMap = chunkMap;
    }

    @Override
    public boolean keep(long pos) {
        ChunkHolder holder = ((ChunkMapUpdatingInvoker) this.chunkMap).ga2$updatingChunk(pos);
        if (holder == null || holder.getLastAvailable() == null) {
            return false;
        }
        ChunkPos at = new ChunkPos(pos);
        this.distanceManager.addTicket(TICKET, at, ChunkLevel.MAX_LEVEL, at);
        return true;
    }

    @Override
    public void letGo(long pos) {
        ChunkPos at = new ChunkPos(pos);
        this.distanceManager.removeTicket(TICKET, at, ChunkLevel.MAX_LEVEL, at);
    }
}
