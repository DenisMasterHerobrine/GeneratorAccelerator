package dev.denismasterherobrine.generatoraccelerator2.mixin.storage;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.serialization.Codec;
import dev.denismasterherobrine.generatoraccelerator2.storage.SectionCodec;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A level's section codecs encode and decode through GA2's shortcuts ({@link SectionCodec}, {@code storage.FastPalettes}). Every
 * case the shortcuts do not cover goes to the codec vanilla made here. Applied late so the mixin plugin sees other mods' changes.
 */
@Mixin(value = PalettedContainerFactory.class, priority = 5000)
public abstract class PalettedContainerFactoryMixin {
    @WrapOperation(method = "create", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/chunk/PalettedContainer;codecRW(Lcom/mojang/serialization/Codec;Lnet/minecraft/world/level/chunk/Strategy;Ljava/lang/Object;)Lcom/mojang/serialization/Codec;"))
    private static Codec<PalettedContainer<BlockState>> ga2$states(Codec<BlockState> element, Strategy<BlockState> strategy, Object defaultValue,
                                                                   Operation<Codec<PalettedContainer<BlockState>>> original) {
        return SectionCodec.states(original.call(element, strategy, defaultValue), element, strategy);
    }

    @WrapOperation(method = "create", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/chunk/PalettedContainer;codecRO(Lcom/mojang/serialization/Codec;Lnet/minecraft/world/level/chunk/Strategy;Ljava/lang/Object;)Lcom/mojang/serialization/Codec;"))
    private static Codec<PalettedContainerRO<Holder<Biome>>> ga2$biomes(Codec<Holder<Biome>> element, Strategy<Holder<Biome>> strategy, Object defaultValue,
                                                                        Operation<Codec<PalettedContainerRO<Holder<Biome>>>> original) {
        return SectionCodec.biomes(original.call(element, strategy, defaultValue), element, strategy);
    }
}
