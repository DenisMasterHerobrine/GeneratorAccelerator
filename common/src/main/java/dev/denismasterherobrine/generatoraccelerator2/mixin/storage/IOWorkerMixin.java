package dev.denismasterherobrine.generatoraccelerator2.mixin.storage;

import com.mojang.datafixers.util.Either;
import com.mojang.logging.LogUtils;
import dev.denismasterherobrine.generatoraccelerator2.memory.WriteBacklog;
import dev.denismasterherobrine.generatoraccelerator2.storage.Ga2RegionStorage;
import dev.denismasterherobrine.generatoraccelerator2.storage.ParallelRead;
import dev.denismasterherobrine.generatoraccelerator2.storage.Precompress;
import dev.denismasterherobrine.generatoraccelerator2.storage.RegionScan;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.util.thread.ProcessorMailbox;
import net.minecraft.util.thread.StrictQueue;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import javax.annotation.Nullable;

/**
 * Runs the old-chunk scan of a region ({@code createOldDataForRegion}) on the IO thread in 16 tasks of 64 chunks
 * ({@link RegionScan}), so loads queued meanwhile still get their turn.
 * Blending requests this scan before generating near a region for the first time. Saved chunks are compressed on the
 * worker pool ({@link Precompress}), and loaded chunks are parsed on the worker pool ({@link ParallelRead}). While many saves
 * wait, one is written after each request ({@link WriteBacklog}).
 */
@Mixin(value = IOWorker.class, priority = 5000)
public abstract class IOWorkerMixin implements WriteBacklog.Storage {
    @Unique
    private static final Logger GA2$LOGGER = LogUtils.getLogger();
    /** IOWorker's own logger name, so read failures read as vanilla's. */
    @Unique
    private static final Logger GA2$IO_LOGGER = LoggerFactory.getLogger(IOWorker.class);

    @Shadow @Final private Map<ChunkPos, IOWorker.PendingStore> pendingWrites;
    @Shadow @Final private RegionFileStorage storage;
    @Shadow @Final private ProcessorMailbox<StrictQueue.IntRunnable> mailbox;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void ga2$register(RegionStorageInfo info, Path folder, boolean sync, CallbackInfo ci) {
        if ("chunk".equals(info.type())) {
            WriteBacklog.opened(this);
        }
    }

    @Override
    public int ga2$pendingWrites() {
        return this.pendingWrites.size();
    }

    @Shadow
    private void runStore(ChunkPos pos, IOWorker.PendingStore store) {
        throw new AssertionError();
    }

    /**
     * Writes the oldest waiting save after each request while many wait. Vanilla writes only when no request waits, so a steady
     * stream of loads holds the writes back and their tags fill the heap ({@link WriteBacklog}). The IO thread then writes at
     * the pace it answers requests, and generation that waits for its loads waits for the disk too. The request's own answer
     * is already given. After a write this runs again, so that write is followed by one more.
     */
    @Inject(method = "tellStorePending", at = @At("HEAD"))
    private void ga2$writeAlong(CallbackInfo ci) {
        if (!WriteBacklog.writeAlong(this.pendingWrites.size())) {
            return;
        }
        Iterator<Map.Entry<ChunkPos, IOWorker.PendingStore>> oldest = this.pendingWrites.entrySet().iterator();
        Map.Entry<ChunkPos, IOWorker.PendingStore> entry = oldest.next();
        oldest.remove();
        this.runStore(entry.getKey(), entry.getValue());
        WriteBacklog.WRITTEN_ALONG.increment();
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void ga2$closing(CallbackInfo ci) {
        WriteBacklog.closed(this);
    }

    @Shadow
    private <T> CompletableFuture<T> submitTask(Supplier<Either<T, Exception>> task) {
        throw new AssertionError();
    }

    @Shadow
    private boolean isOldChunk(CompoundTag tag) {
        throw new AssertionError();
    }

    @Inject(method = "createOldDataForRegion", at = @At("HEAD"), cancellable = true)
    private void ga2$scanRegionOnce(int regionX, int regionZ, CallbackInfoReturnable<CompletableFuture<BitSet>> cir) {
        if (!RegionScan.enabled() || this.mailbox == null) {
            // a mod that replaced the IO worker's mailbox (C2ME's chunk IO) answers through its own code
            return;
        }
        // the region's chunks in vanilla's order, in 16 tasks of 64 so loads queued meanwhile still get their turn
        List<ChunkPos> chunks = ChunkPos.rangeClosed(ChunkPos.minFromRegion(regionX, regionZ), ChunkPos.maxFromRegion(regionX, regionZ)).toList();
        BitSet bitset = new BitSet();
        CompletableFuture<BitSet> result = CompletableFuture.completedFuture(bitset);
        for (int from = 0; from < chunks.size(); from += RegionScan.BATCH) {
            List<ChunkPos> batch = chunks.subList(from, Math.min(chunks.size(), from + RegionScan.BATCH));
            result = result.thenCompose(bits -> this.submitTask(() -> {
                for (ChunkPos pos : batch) {
                    this.ga2$scanOne(pos, bits);
                }
                return Either.left(bits);
            }));
        }
        RegionScan.SCANS.increment();
        cir.setReturnValue(result);
    }

    /** The body of the {@code scanChunk} task plus vanilla's old-chunk test, for one chunk. */
    @Unique
    private void ga2$scanOne(ChunkPos pos, BitSet bitset) {
        CollectFields fields = new CollectFields(new FieldSelector(IntTag.TYPE, "DataVersion"), new FieldSelector(CompoundTag.TYPE, "blending_data"));
        try {
            IOWorker.PendingStore pending = this.pendingWrites.get(pos);
            if (pending != null) {
                if (pending.data != null) {
                    pending.data.acceptAsRoot(fields);
                }
            } else {
                this.storage.scanChunk(pos, fields);
            }
        } catch (Exception exception) {
            GA2$LOGGER.warn("Failed to scan chunk {}", pos, exception);
            return;
        }
        if (fields.getResult() instanceof CompoundTag tag && this.isOldChunk(tag)) {
            bitset.set(pos.getRegionLocalZ() * 32 + pos.getRegionLocalX());
        }
    }

    /**
     * {@code loadAsync} with the parse moved to the worker pool ({@link ParallelRead}). The IO thread task matches vanilla up
     * to reading the chunk bytes. A pending write is copied on the pool too, an absent chunk answers empty, and a failure is
     * logged and passed on. The returned future completes when the pool has parsed the bytes or copied the tag.
     */
    @Inject(method = "loadAsync", at = @At("HEAD"), cancellable = true)
    private void ga2$parallelRead(ChunkPos pos, CallbackInfoReturnable<CompletableFuture<Optional<CompoundTag>>> cir) {
        if (this.mailbox == null || !ParallelRead.appliesTo(this.storage.info().type()) || !ParallelRead.enabled()) {
            return;
        }
        CompletableFuture<Object> read = this.submitTask(() -> {
            IOWorker.PendingStore pending = this.pendingWrites.get(pos);
            if (pending != null) {
                // PendingStore.copyData, with the copy made on the pool. A stored tag does not change, and a newer save of the
                // chunk replaces the pending tag instead of changing it
                CompoundTag data = pending.data;
                return Either.left(data == null ? Optional.empty() : new ParallelRead.Pending(data));
            }
            try {
                return Either.left(((Ga2RegionStorage) (Object) this.storage).ga2$readRaw(pos));
            } catch (Exception exception) {
                GA2$IO_LOGGER.warn("Failed to read chunk {}", pos, exception);
                return Either.right(exception);
            }
        });
        CompletableFuture<Optional<CompoundTag>> out = new CompletableFuture<>();
        read.whenComplete((result, failure) -> {
            if (failure != null) {
                out.completeExceptionally(failure);
            } else if (result instanceof ParallelRead.Unparsed raw) {
                ParallelRead.parseLater(raw, pos, out);
            } else if (result instanceof ParallelRead.Pending pending) {
                ParallelRead.copyLater(pending, out);
            } else {
                @SuppressWarnings("unchecked")
                Optional<CompoundTag> tag = (Optional<CompoundTag>) result;
                out.complete(tag);
            }
        });
        cir.setReturnValue(out);
    }

    @Inject(method = "store", at = @At("HEAD"))
    private void ga2$precompress(ChunkPos pos, @Nullable CompoundTag tag, CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        if (tag != null && this.mailbox != null && Precompress.enabled() && "chunk".equals(this.storage.info().type())) {
            ((Ga2RegionStorage) (Object) this.storage).ga2$precompress().submit(pos, tag);
        }
    }
}
