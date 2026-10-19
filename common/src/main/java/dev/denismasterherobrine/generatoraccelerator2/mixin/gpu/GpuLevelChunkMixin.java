package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import dev.denismasterherobrine.generatoraccelerator2.gpu.decor.GpuDecor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A chunk the GPU kept a copy of becomes a full chunk. All decoration writing into it is done, so the copy and its write log are dropped. */
@Mixin(LevelChunk.class)
public abstract class GpuLevelChunkMixin {
    @Inject(method = "<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ProtoChunk;Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;)V",
        at = @At("TAIL"))
    private void ga2$untrack(ServerLevel level, ProtoChunk proto, LevelChunk.PostLoadProcessor postLoad, CallbackInfo ci) {
        GpuDecor.untrack(proto, ((LevelChunk) (Object) this).getSections());
    }
}
