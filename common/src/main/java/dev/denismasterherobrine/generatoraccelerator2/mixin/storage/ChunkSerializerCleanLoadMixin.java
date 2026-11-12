package dev.denismasterherobrine.generatoraccelerator2.mixin.storage;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.storage.CleanLoads;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A clean load of a full chunk leaves out the three marks of {@code read} that change nothing on disk ({@link CleanLoads}). The
 * chunk keeps the unsaved flag it had before each call.
 */
@Mixin(value = ChunkSerializer.class, priority = 5000)
public abstract class ChunkSerializerCleanLoadMixin {
    @Inject(method = "read", at = @At("HEAD"))
    private static void ga2$cleanBegin(ServerLevel level, PoiManager poi, RegionStorageInfo info, ChunkPos pos, CompoundTag tag,
                                       CallbackInfoReturnable<ProtoChunk> cir) {
        CleanLoads.begin(tag);
    }

    @Inject(method = "read", at = @At("RETURN"))
    private static void ga2$cleanEnd(ServerLevel level, PoiManager poi, RegionStorageInfo info, ChunkPos pos, CompoundTag tag,
                                     CallbackInfoReturnable<ProtoChunk> cir) {
        CleanLoads.end(cir.getReturnValue());
    }

    @WrapOperation(method = "read", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;setLightCorrect(Z)V"))
    private static void ga2$lightUnmarked(ChunkAccess chunk, boolean correct, Operation<Void> original) {
        if (CleanLoads.reading(chunk)) {
            boolean unsaved = chunk.isUnsaved();
            original.call(chunk, correct);
            chunk.setUnsaved(unsaved);
        } else {
            original.call(chunk, correct);
        }
    }

    @WrapOperation(method = "read", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;setAllStarts(Ljava/util/Map;)V"))
    private static void ga2$startsUnmarked(ChunkAccess chunk, Map<?, ?> starts, Operation<Void> original) {
        if (CleanLoads.reading(chunk)) {
            boolean unsaved = chunk.isUnsaved();
            original.call(chunk, starts);
            chunk.setUnsaved(unsaved);
        } else {
            original.call(chunk, starts);
        }
    }

    @WrapOperation(method = "read", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;setAllReferences(Ljava/util/Map;)V"))
    private static void ga2$referencesUnmarked(ChunkAccess chunk, Map<?, ?> references, Operation<Void> original) {
        if (CleanLoads.reading(chunk)) {
            boolean unsaved = chunk.isUnsaved();
            original.call(chunk, references);
            chunk.setUnsaved(unsaved);
        } else {
            original.call(chunk, references);
        }
    }
}
