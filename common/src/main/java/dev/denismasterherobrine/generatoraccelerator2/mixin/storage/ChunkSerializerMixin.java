package dev.denismasterherobrine.generatoraccelerator2.mixin.storage;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import dev.denismasterherobrine.generatoraccelerator2.storage.SavePrep;
import dev.denismasterherobrine.generatoraccelerator2.storage.SectionCodec;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Builds a saved chunk's section tags directly ({@link SectionCodec}). Every other encode in {@code write} is untouched. */
@Mixin(value = ChunkSerializer.class, priority = 5000)
public abstract class ChunkSerializerMixin {
    @Shadow @Final private static Codec<PalettedContainer<BlockState>> BLOCK_STATE_CODEC;

    @WrapOperation(method = "write", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/chunk/storage/ChunkSerializer;makeBiomeCodec(Lnet/minecraft/core/Registry;)Lcom/mojang/serialization/Codec;"))
    private static Codec<PalettedContainerRO<Holder<Biome>>> ga2$biomeCodec(Registry<Biome> registry, Operation<Codec<PalettedContainerRO<Holder<Biome>>>> original,
                                                                             @Share("ga2Biomes") LocalRef<Registry<Biome>> biomes,
                                                                             @Share("ga2BiomeCodec") LocalRef<Codec<PalettedContainerRO<Holder<Biome>>>> biomeCodec) {
        Codec<PalettedContainerRO<Holder<Biome>>> codec = original.call(registry);
        biomes.set(registry);
        biomeCodec.set(codec);
        return codec;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @WrapOperation(method = "write", at = @At(value = "INVOKE",
        target = "Lcom/mojang/serialization/Codec;encodeStart(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;"))
    private static DataResult ga2$encodeSection(Codec codec, DynamicOps ops, Object value, Operation<DataResult> original,
                                                @Share("ga2Biomes") LocalRef<Registry<Biome>> biomes,
                                                @Share("ga2BiomeCodec") LocalRef<Codec<PalettedContainerRO<Holder<Biome>>>> biomeCodec) {
        if (ops == NbtOps.INSTANCE && SectionCodec.enabled()) {
            Tag tag = null;
            if (codec == BLOCK_STATE_CODEC && value instanceof PalettedContainer<?> states) {
                // Encoded ahead on a worker while the chunk was unloading (SavePrep). Taken only if the section is unchanged.
                tag = SavePrep.take(states, () -> SectionCodec.blockStates((PalettedContainer<BlockState>) states));
                if (tag == null) {
                    tag = SectionCodec.blockStates((PalettedContainer<BlockState>) states);
                }
            } else if (codec == biomeCodec.get() && biomes.get() != null && value instanceof PalettedContainerRO<?> container) {
                Registry<Biome> registry = biomes.get();
                tag = container instanceof PalettedContainer<?> pc
                    ? SavePrep.take(pc, () -> SectionCodec.biomes((PalettedContainerRO<Holder<Biome>>) container, registry)) : null;
                if (tag == null) {
                    tag = SectionCodec.biomes((PalettedContainerRO<Holder<Biome>>) container, registry);
                }
            }
            if (tag != null) {
                return DataResult.success(tag);
            }
        }
        return original.call(codec, ops, value);
    }
}
