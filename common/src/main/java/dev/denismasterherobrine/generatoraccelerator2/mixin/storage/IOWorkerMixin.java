package dev.denismasterherobrine.generatoraccelerator2.mixin.storage;

import com.mojang.logging.LogUtils;
import dev.denismasterherobrine.generatoraccelerator2.storage.Ga2RegionStorage;
import dev.denismasterherobrine.generatoraccelerator2.storage.ParallelRead;
import dev.denismasterherobrine.generatoraccelerator2.storage.Precompress;
import dev.denismasterherobrine.generatoraccelerator2.storage.RegionScan;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.util.thread.PriorityConsecutiveExecutor;
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
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Runs the old-chunk scan of a region ({@code createOldDataForRegion}) on the IO thread in 16 tasks of 64 chunks
 * ({@link RegionScan}), so loads queued meanwhile still get their turn and no pool worker waits on 1024 scans.
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

    @Shadow @Final private SequencedMap<ChunkPos, IOWorker.PendingStore> pendingWrites;
    @Shadow @Final private RegionFileStorage storage;
    @Shadow private PriorityConsecutiveExecutor consecutiveExecutor;

    @Shadow
    private <T> CompletableFuture<T> submitTask(Supplier<T> task) {
        throw new AssertionError();
    }

    @Shadow
    private boolean isOldChunk(CompoundTag tag) {
        throw new AssertionError();
    }

    @Inject(method = "createOldDataForRegion", at = @At("HEAD"), cancellable = true)
    private void ga2$scanRegionOnce(int regionX, int regionZ, CallbackInfoReturnable<CompletableFuture<BitSet>> cir) {
        if (!RegionScan.enabled() || this.consecutiveExecutor == null) {
            // a mod that replaced the IO worker's executor (C2ME's chunk IO) answers through its own code
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
                return bits;
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
        if (this.consecutiveExecutor == null || !ParallelRead.appliesTo(this.storage.info().type()) || !ParallelRead.enabled()) {
            return;
        }
        CompletableFuture<Object> read = this.submitTask(() -> {
            IOWorker.PendingStore pending = this.pendingWrites.get(pos);
            if (pending != null) {
                // PendingStore.copyData
                CompoundTag data = pending.data;
                return Optional.ofNullable(data == null ? null : data.copy());
            }
            try {
                return ((Ga2RegionStorage) (Object) this.storage).ga2$readRaw(pos);
            } catch (Exception exception) {
                GA2$IO_LOGGER.warn("Failed to read chunk {}", pos, exception);
                return new ParallelRead.Failed(exception);
            }
        });
        CompletableFuture<Optional<CompoundTag>> out = new CompletableFuture<>();
        read.whenComplete((result, failure) -> {
            if (failure != null) {
                out.completeExceptionally(failure);
            } else if (result instanceof ParallelRead.Failed failed) {
                out.completeExceptionally(failed.exception());
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

    /**
     * The saved chunk's tag goes to the worker pool for compression as soon as the IO thread has it. Vanilla's chunk map hands
     * the IO worker a supplier that waits for the tag's encoding on the pool. The IO thread then writes the chunk in a later
     * background task, by which time the pool has compressed it.
     */
    @ModifyVariable(method = "store(Lnet/minecraft/world/level/ChunkPos;Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;",
        at = @At("HEAD"), argsOnly = true)
    private Supplier<CompoundTag> ga2$precompress(Supplier<CompoundTag> supplier, ChunkPos pos) {
        if (this.consecutiveExecutor == null || !Precompress.enabled() || !"chunk".equals(this.storage.info().type())) {
            return supplier;
        }
        Precompress precompress = ((Ga2RegionStorage) (Object) this.storage).ga2$precompress();
        return () -> {
            CompoundTag tag = supplier.get();
            if (tag != null && Precompress.enabled()) {
                precompress.submit(pos, tag);
            }
            return tag;
        };
    }
}
