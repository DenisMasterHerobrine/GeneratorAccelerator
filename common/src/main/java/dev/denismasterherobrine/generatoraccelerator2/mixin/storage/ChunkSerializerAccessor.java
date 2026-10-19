package dev.denismasterherobrine.generatoraccelerator2.mixin.storage;

import com.mojang.serialization.Codec;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The two section codecs {@code ChunkSerializer.read} decodes with, for {@code storage.LoadPrep}. */
@Mixin(ChunkSerializer.class)
public interface ChunkSerializerAccessor {
    @Accessor("BLOCK_STATE_CODEC")
    static Codec<PalettedContainer<BlockState>> ga2$blockStateCodec() {
        throw new AssertionError();
    }

    @Invoker("makeBiomeCodec")
    static Codec<PalettedContainerRO<Holder<Biome>>> ga2$makeBiomeCodec(Registry<Biome> registry) {
        throw new AssertionError();
    }
}
