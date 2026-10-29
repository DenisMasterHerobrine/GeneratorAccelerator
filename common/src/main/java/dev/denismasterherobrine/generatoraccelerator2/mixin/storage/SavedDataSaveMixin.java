package dev.denismasterherobrine.generatoraccelerator2.mixin.storage;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.storage.SavedDataRetry;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A saved data another thread changes while it is written is written again ({@link SavedDataRetry}). */
@Mixin(SavedData.class)
public abstract class SavedDataSaveMixin {
    @WrapOperation(method = "save(Ljava/io/File;Lnet/minecraft/core/HolderLookup$Provider;)V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/saveddata/SavedData;save(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/nbt/CompoundTag;"))
    private CompoundTag ga2$writeAgainOnRace(SavedData data, CompoundTag tag, HolderLookup.Provider registries, Operation<CompoundTag> original) {
        return SavedDataRetry.save(data, tag, t -> original.call(data, t, registries));
    }
}
