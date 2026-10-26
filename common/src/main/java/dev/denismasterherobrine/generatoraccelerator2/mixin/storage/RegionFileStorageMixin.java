package dev.denismasterherobrine.generatoraccelerator2.mixin.storage;

import dev.denismasterherobrine.generatoraccelerator2.storage.FastRegionWrite;
import dev.denismasterherobrine.generatoraccelerator2.storage.Ga2RegionStorage;
import dev.denismasterherobrine.generatoraccelerator2.storage.ParallelRead;
import dev.denismasterherobrine.generatoraccelerator2.storage.Precompress;
import dev.denismasterherobrine.generatoraccelerator2.storage.RegionSync;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.profiling.jfr.JvmProfiler;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Writes a chunk into its region file through {@link FastRegionWrite} (deflate region files only). Uses the bytes the
 * worker pool already compressed, if any ({@link Precompress}). Also reads raw chunk bytes for {@link ParallelRead}.
 */
@Mixin(value = RegionFileStorage.class, priority = 5000)
public abstract class RegionFileStorageMixin implements Ga2RegionStorage {
    @Unique
    private final Precompress ga2$precompress = new Precompress();

    @Override
    public Precompress ga2$precompress() {
        return this.ga2$precompress;
    }

    @Shadow @Final private Path folder;

    @Shadow
    private RegionFile getRegionFile(ChunkPos pos) throws IOException {
        throw new AssertionError();
    }

    @Inject(method = "write", at = @At("HEAD"), cancellable = true)
    private void ga2$write(ChunkPos pos, @Nullable CompoundTag tag, CallbackInfo ci) throws IOException {
        if (tag == null || !FastRegionWrite.enabled()) {
            // Every write of the chunk settles its precompress submission. This write does not use it.
            this.ga2$precompress.discard(pos);
            return;
        }
        RegionFile file;
        try {
            file = this.getRegionFile(pos);
        } catch (IOException | RuntimeException e) {
            this.ga2$precompress.discard(pos);
            throw e;
        }
        RegionFileAccessor access = (RegionFileAccessor) file;
        if (access.ga2$version() != RegionFileVersion.VERSION_DEFLATE) {
            this.ga2$precompress.discard(pos);
            return;
        }
        ByteBuffer buffer = null;
        if (Precompress.enabled()) {
            buffer = this.ga2$precompress.take(pos, tag);
        } else {
            this.ga2$precompress.discard(pos);
        }
        if (buffer == null) {
            buffer = FastRegionWrite.encode(tag, RegionFileVersion.VERSION_DEFLATE.getId());
        }
        JvmProfiler.INSTANCE.onRegionFileWrite(access.ga2$info(), pos, access.ga2$version(), buffer.getInt(0));
        access.ga2$write(pos, buffer);
        ci.cancel();
    }

    /** A storage that would sync every write opens its files without DSYNC and syncs them in batches ({@link RegionSync}). */
    @ModifyArg(method = "getRegionFile", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/storage/RegionFile;<init>("
        + "Lnet/minecraft/world/level/chunk/storage/RegionStorageInfo;Ljava/nio/file/Path;Ljava/nio/file/Path;Z)V"), index = 3)
    private boolean ga2$batchedSync(boolean sync) {
        return RegionSync.openUnsynced(sync, this.folder);
    }

    /** {@code read} without the parse ({@link ParallelRead}). Uses the same region file lookup and stream. */
    @Override
    public Object ga2$readRaw(ChunkPos pos) throws IOException {
        RegionFile file = this.getRegionFile(pos);
        boolean[] external = ParallelRead.EXTERNAL.get();
        external[0] = false;
        DataInputStream in = file.getChunkDataInputStream(pos);
        if (in == null) {
            return Optional.empty();
        }
        if (external[0]) {
            // The stream reads lazily from the external file. Parse now, as vanilla does, before a write replaces it.
            ParallelRead.ON_IO_THREAD.increment();
            try (DataInputStream stream = in) {
                return Optional.ofNullable(NbtIo.read(stream));
            }
        }
        return new ParallelRead.Unparsed(in);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void ga2$close(CallbackInfo ci) {
        this.ga2$precompress.clear();
    }
}
