package dev.denismasterherobrine.generatoraccelerator2.mixin.storage;

import com.mojang.datafixers.util.Either;
import com.mojang.logging.LogUtils;
import dev.denismasterherobrine.generatoraccelerator2.storage.Ga2RegionStorage;
import dev.denismasterherobrine.generatoraccelerator2.storage.ParallelRead;
import dev.denismasterherobrine.generatoraccelerator2.storage.Precompress;
import dev.denismasterherobrine.generatoraccelerator2.storage.RegionScan;
import java.nio.file.Path;
import java.util.BitSet;
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
 * worker pool ({@link Precompress}), and loaded chunks are parsed on the worker pool ({@link ParallelRead}).
 */
@Mixin(value = IOWorker.class, priority = 5000)
public abstract class IOWorkerMixin {
    @Unique
    private static final Logger GA2$LOGGER = LogUtils.getLogger();
    /** IOWorker's own logger name, so read failures read as vanilla's. */
    @Unique
    private static final Logger GA2$IO_LOGGER = LoggerFactory.getLogger(IOWorker.class);

    @Shadow @Final private Map<ChunkPos, IOWorker.PendingStore> pendingWrites;
    @Shadow @Final private RegionFileStorage storage;
    @Shadow @Final private ProcessorMailbox<StrictQueue.IntRunnable> mailbox;

    /** The storage's kind as its owner names it: chunk, poi or entities. */
    @Unique
    private String ga2$type;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void ga2$recordType(Path folder, boolean sync, String name, CallbackInfo ci) {
        this.ga2$type = name;
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
     * to reading the chunk bytes. A pending write is copied, an absent chunk answers empty, and a failure is logged and
     * passed on. The returned future completes when the pool has parsed the bytes.
     */
    @Inject(method = "loadAsync", at = @At("HEAD"), cancellable = true)
    private void ga2$parallelRead(ChunkPos pos, CallbackInfoReturnable<CompletableFuture<Optional<CompoundTag>>> cir) {
        if (this.mailbox == null || !ParallelRead.appliesTo(this.ga2$type) || !ParallelRead.enabled()) {
            return;
        }
        CompletableFuture<Object> read = this.submitTask(() -> {
            IOWorker.PendingStore pending = this.pendingWrites.get(pos);
            if (pending != null) {
                // PendingStore.copyData
                CompoundTag data = pending.data;
                return Either.left(Optional.ofNullable(data == null ? null : data.copy()));
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
        if (tag != null && this.mailbox != null && Precompress.enabled() && "chunk".equals(this.ga2$type)) {
            ((Ga2RegionStorage) (Object) this.storage).ga2$precompress().submit(pos, tag);
        }
    }
}
