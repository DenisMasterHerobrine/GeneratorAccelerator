package dev.denismasterherobrine.generatoraccelerator2.compat.chunky;

import dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload.ChunkMapUpdatingInvoker;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.TicketStorage;

/** GA2's tickets on the chunks {@link ChunkyHolds} keeps, in one dimension. */
public final class ChunkyHoldTickets implements ChunkyHolds.Tickets {
    /**
     * Loads, without a timeout, and is not saved. A kept chunk's ticket goes when the chunk is let go. Tickets compare their
     * types by identity, so no other ticket matches this one.
     */
    static final TicketType TICKET = new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING);

    private final TicketStorage tickets;
    private final ChunkMap chunkMap;

    public ChunkyHoldTickets(TicketStorage tickets, ChunkMap chunkMap) {
        this.tickets = tickets;
        this.chunkMap = chunkMap;
    }

    @Override
    public boolean keep(long pos) {
        ChunkHolder holder = ((ChunkMapUpdatingInvoker) this.chunkMap).ga2$updatingChunk(pos);
        if (holder == null || holder.getLatestChunk() == null) {
            return false;
        }
        this.tickets.addTicket(pos, new Ticket(TICKET, ChunkLevel.MAX_LEVEL));
        return true;
    }

    @Override
    public void letGo(long pos) {
        this.tickets.removeTicket(pos, new Ticket(TICKET, ChunkLevel.MAX_LEVEL));
    }
}
