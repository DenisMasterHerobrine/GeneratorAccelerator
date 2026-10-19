package dev.denismasterherobrine.generatoraccelerator2.mixin.storage;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.storage.SavePrep;
import java.util.function.Function;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.ticks.SerializableTickContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A saved chunk's block and fluid tick lists, encoded ahead when the chunk was unloading and unchanged since ({@link SavePrep}). */
@Mixin(value = ChunkSerializer.class, priority = 5000)
public abstract class ChunkSerializerTicksMixin {
    @SuppressWarnings({ "rawtypes", "unchecked" })
    @WrapOperation(method = "saveTicks", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/ticks/SerializableTickContainer;save(JLjava/util/function/Function;)Lnet/minecraft/nbt/Tag;"))
    private static Tag ga2$preparedTicks(SerializableTickContainer container, long gameTime, Function id, Operation<Tag> original) {
        Tag prepared = SavePrep.takeTicks(container, gameTime, () -> original.call(container, gameTime, id));
        return prepared != null ? prepared : original.call(container, gameTime, id);
    }
}
