package dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.chunkload.ChunkLoadStats;
import dev.denismasterherobrine.generatoraccelerator2.compat.chunky.ChunkyHolds;
import dev.denismasterherobrine.generatoraccelerator2.memory.UnloadBacklog;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.Queue;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The early saves of {@code processUnloads}: each tick vanilla saves up to 20 loaded full chunks, walking every holder of the
 * map until it has 20.
 *
 * <ul>
 * <li>The chunks {@link ChunkyHolds} keeps are left out. They are saved when they are let go, with their sections encoded
 * ahead.</li>
 * <li>A holder without a ticket is left out too. Its chunk unloads within a tick or two, and the unload saves it with its
 * sections encoded ahead ({@code storage.SavePrep}). Saved here, its sections would be encoded on the server thread, and the
 * unload would then find nothing to save.</li>
 * <li>The loop asks for the time left once per holder. In singleplayer that is a JNI call into GLFW. It now asks every eighth
 * holder, and after every save.</li>
 * </ul>
 * Only this per-tick loop changes. Autosaves, {@code /save-all} and shutdown go through {@code saveAllChunks} and still save
 * every chunk.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapKeptSaveMixin {
    @Shadow @Final ServerLevel level;
    @Shadow @Final private Long2ObjectLinkedOpenHashMap<ChunkHolder> pendingUnloads;
    @Shadow @Final private Queue<Runnable> unloadQueue;

    @Unique
    private static final boolean GA2_LEAVE_UNLOADING = !"false".equals(System.getProperty("generatoraccelerator2.earlySaves.leaveUnloading"));
    /** How many holders share one answer of the time left. 1 asks for every holder, as vanilla does. */
    @Unique
    private static final int GA2_CLOCK_EVERY = Math.max(1, Integer.getInteger("generatoraccelerator2.earlySaves.clockEvery", 8));

    @Unique private int ga2$sinceClock;
    @Unique private boolean ga2$hadTime;

    @Inject(method = "processUnloads", at = @At("HEAD"))
    private void ga2$askClockFirst(BooleanSupplier hasMoreTime, CallbackInfo ci) {
        this.ga2$sinceClock = GA2_CLOCK_EVERY;
    }

    @Inject(method = "processUnloads", at = @At("TAIL"))
    private void ga2$reportBacklog(BooleanSupplier hasMoreTime, CallbackInfo ci) {
        UnloadBacklog.report(this, this.pendingUnloads.size() + this.unloadQueue.size());
    }

    @WrapOperation(method = "processUnloads", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ChunkMap;saveChunkIfNeeded(Lnet/minecraft/server/level/ChunkHolder;)Z"))
    private boolean ga2$notWhileKept(ChunkMap self, ChunkHolder holder, Operation<Boolean> original) {
        if (!holder.wasAccessibleSinceLastSave()) {
            // vanilla's own first test, read here before the lookups below: the loop walks every holder of the map
            return false;
        }
        if (GA2_LEAVE_UNLOADING && !ChunkLevel.isLoaded(holder.getTicketLevel())) {
            ChunkLoadStats.EARLY_SAVES_LEFT.increment();
            return false;
        }
        ChunkyHolds holds = ((ChunkyHolds.Owner) this.level.getChunkSource()).ga2$chunkyHolds();
        if (holds != null && holds.isKept(holder.getPos().toLong())) {
            return false;
        }
        boolean saved = original.call(self, holder);
        if (saved) {
            // a save takes time. The next holder asks the clock
            this.ga2$sinceClock = GA2_CLOCK_EVERY;
        }
        return saved;
    }

    /** The time check of the early-save loop, the third one in {@code processUnloads}. */
    @WrapOperation(method = "processUnloads", at = @At(value = "INVOKE", target = "Ljava/util/function/BooleanSupplier;getAsBoolean()Z", ordinal = 2),
        require = 0)
    private boolean ga2$fewerClockReads(BooleanSupplier hasMoreTime, Operation<Boolean> original) {
        if (++this.ga2$sinceClock >= GA2_CLOCK_EVERY || !this.ga2$hadTime) {
            this.ga2$sinceClock = 0;
            this.ga2$hadTime = original.call(hasMoreTime);
        }
        return this.ga2$hadTime;
    }
}
