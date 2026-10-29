package dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload;

import dev.denismasterherobrine.generatoraccelerator2.compat.chunky.ChunkyHoldTickets;
import dev.denismasterherobrine.generatoraccelerator2.compat.chunky.ChunkyHolds;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells {@link ChunkyHolds} when Chunky adds and removes its tickets, and ticks it. Chunky does both on the server thread. Its
 * ticket keeps one chunk full (distance 0). Only observes: every ticket is still added and removed as before.
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheHoldsMixin implements ChunkyHolds.Owner {
    @Shadow
    @Final
    private TicketStorage ticketStorage;

    @Shadow
    @Final
    public ChunkMap chunkMap;

    @Shadow
    @Final
    Thread mainThread;

    @Unique
    private ChunkyHolds ga2$holds;

    @Inject(method = "addTicketWithRadius", at = @At("TAIL"))
    private void ga2$chunkyAsked(TicketType type, ChunkPos pos, int distance, CallbackInfo ci) {
        if (distance == 0 && ChunkyHolds.isChunky(type) && Thread.currentThread() == this.mainThread) {
            this.ga2$holds().asked(pos.x(), pos.z(), System.nanoTime());
        }
    }

    @Inject(method = "removeTicketWithRadius", at = @At("TAIL"))
    private void ga2$chunkyDone(TicketType type, ChunkPos pos, int distance, CallbackInfo ci) {
        if (distance == 0 && this.ga2$holds != null && ChunkyHolds.isChunky(type) && Thread.currentThread() == this.mainThread) {
            this.ga2$holds.done(pos.x(), pos.z(), System.nanoTime());
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void ga2$tickHolds(BooleanSupplier hasTime, boolean tickChunks, CallbackInfo ci) {
        if (this.ga2$holds != null) {
            this.ga2$holds.tick(System.nanoTime(), ChunkyHolds.cap());
        }
    }

    @Override
    public ChunkyHolds ga2$chunkyHolds() {
        return this.ga2$holds;
    }

    @Unique
    private ChunkyHolds ga2$holds() {
        ChunkyHolds holds = this.ga2$holds;
        if (holds == null) {
            holds = new ChunkyHolds(new ChunkyHoldTickets(this.ticketStorage, this.chunkMap), ChunkLevel.RADIUS_AROUND_FULL_CHUNK, ChunkyHolds.cap());
            this.ga2$holds = holds;
        }
        return holds;
    }
}
