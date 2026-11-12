package dev.denismasterherobrine.generatoraccelerator2.mixin.storage;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.serialization.MapCodec;
import dev.denismasterherobrine.generatoraccelerator2.storage.CleanLoads;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Clean loads ({@link CleanLoads}): a tag the data fixer changed is noted for {@code read}, and a clean chunk with scheduled
 * ticks is marked before its save.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapCleanLoadMixin {
    @WrapOperation(method = "upgradeChunkTag(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ChunkMap;upgradeChunkTag(Lnet/minecraft/resources/ResourceKey;Ljava/util/function/Supplier;"
            + "Lnet/minecraft/nbt/CompoundTag;Ljava/util/Optional;)Lnet/minecraft/nbt/CompoundTag;"))
    private CompoundTag ga2$noteUpgrade(ChunkMap self, ResourceKey<Level> level, Supplier<DimensionDataStorage> storage, CompoundTag tag,
                                        Optional<ResourceKey<MapCodec<? extends ChunkGenerator>>> generator, Operation<CompoundTag> original) {
        CompoundTag out = original.call(self, level, storage, tag, generator);
        CleanLoads.upgraded(tag, out);
        return out;
    }

    @Inject(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z", at = @At("HEAD"))
    private void ga2$cleanBeforeSave(ChunkAccess chunk, CallbackInfoReturnable<Boolean> cir) {
        CleanLoads.beforeSave(chunk);
    }
}
