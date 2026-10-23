package dev.denismasterherobrine.generatoraccelerator2.mixin.storage;

import dev.denismasterherobrine.generatoraccelerator2.storage.RegionSync;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A region file opened for batched syncing ({@link RegionSync}): its writes mark it dirty, vanilla's own flush and close mark it
 * clean. Applied late so the mixin plugin sees other mods' changes.
 */
@Mixin(value = RegionFile.class, priority = 5000)
public abstract class RegionFileSyncMixin implements RegionSync.Batched {
    @Shadow @Final private FileChannel file;

    @Unique
    private boolean ga2$batched;
    @Unique
    private volatile long ga2$dirtySince;
    @Unique
    private volatile int ga2$dirtyWrites;
    @Unique
    private volatile boolean ga2$closed;

    @Inject(method = "<init>(Lnet/minecraft/world/level/chunk/storage/RegionStorageInfo;Ljava/nio/file/Path;Ljava/nio/file/Path;"
        + "Lnet/minecraft/world/level/chunk/storage/RegionFileVersion;Z)V", at = @At("TAIL"))
    private void ga2$claimed(RegionStorageInfo info, Path path, Path dir, RegionFileVersion version, boolean sync, CallbackInfo ci) {
        this.ga2$batched = !sync && RegionSync.consumeClaim();
    }

    @Inject(method = "write(Lnet/minecraft/world/level/ChunkPos;Ljava/nio/ByteBuffer;)V", at = @At("TAIL"))
    private void ga2$written(ChunkPos pos, ByteBuffer data, CallbackInfo ci) {
        RegionSync.written(this);
    }

    @Inject(method = "clear", at = @At("TAIL"))
    private void ga2$cleared(ChunkPos pos, CallbackInfo ci) {
        RegionSync.written(this);
    }

    @Inject(method = "flush", at = @At("TAIL"))
    private void ga2$flushed(CallbackInfo ci) {
        RegionSync.flushed(this);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void ga2$closing(CallbackInfo ci) {
        this.ga2$closed = true;
        RegionSync.closing(this);
    }

    @Override
    public boolean ga2$batched() {
        return this.ga2$batched;
    }

    @Override
    public void ga2$setBatched(boolean batched) {
        this.ga2$batched = batched;
    }

    @Override
    public long ga2$dirtySince() {
        return this.ga2$dirtySince;
    }

    @Override
    public void ga2$setDirtySince(long nanos) {
        this.ga2$dirtySince = nanos;
    }

    @Override
    public int ga2$dirtyWrites() {
        return this.ga2$dirtyWrites;
    }

    @Override
    public void ga2$setDirtyWrites(int writes) {
        this.ga2$dirtyWrites = writes;
    }

    @Override
    public boolean ga2$closed() {
        return this.ga2$closed;
    }

    @Override
    public FileChannel ga2$channel() {
        return this.file;
    }
}
