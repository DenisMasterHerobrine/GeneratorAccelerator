package dev.denismasterherobrine.generatoraccelerator2.mixin.storage;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import dev.denismasterherobrine.generatoraccelerator2.storage.SavedDataRetry;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A saved data another thread changes while it is encoded is encoded again ({@link SavedDataRetry}). */
@Mixin(SavedDataStorage.class)
public abstract class SavedDataSaveMixin {
    @WrapOperation(method = "encodeUnchecked", at = @At(value = "INVOKE",
        target = "Lcom/mojang/serialization/Codec;encodeStart(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;"))
    private DataResult<Tag> ga2$encodeAgainOnRace(Codec<Object> codec, DynamicOps<Tag> ops, Object data, Operation<DataResult<Tag>> original) {
        return SavedDataRetry.encode(data, () -> original.call(codec, ops, data));
    }
}
