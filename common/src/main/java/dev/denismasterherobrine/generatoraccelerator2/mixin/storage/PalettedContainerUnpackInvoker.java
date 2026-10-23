package dev.denismasterherobrine.generatoraccelerator2.mixin.storage;

import com.mojang.serialization.DataResult;
import net.minecraft.core.IdMap;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The step at the end of the section codecs that builds a container from its palette and data ({@code storage.FastPalettes}). */
@Mixin(PalettedContainer.class)
public interface PalettedContainerUnpackInvoker {
    @Invoker("unpack")
    static <T> DataResult<PalettedContainer<T>> ga2$unpack(IdMap<T> registry, PalettedContainer.Strategy strategy, PalettedContainerRO.PackedData<T> data) {
        throw new AssertionError();
    }
}
