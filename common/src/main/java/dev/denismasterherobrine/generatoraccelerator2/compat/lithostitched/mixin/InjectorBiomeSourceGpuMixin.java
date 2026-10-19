package dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched.LithostitchedGpuBiomes;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The injector source's climate sample and base biome lookup, answered from the GPU when GA2 hands them in
 * ({@link LithostitchedGpuBiomes}). Without a handed value, both calls run as before. GA2 checks that these wrappers apply before
 * the GPU decides a dimension's biomes under injectors. Only applied with Lithostitched loaded.
 */
@Mixin(targets = "dev.worldgen.lithostitched.impl.worldgen.biomeinjector.internal.InjectorBiomeSource")
public abstract class InjectorBiomeSourceGpuMixin {
    private static final String NOISE_BIOME = "getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;";
    // the override's intermediary name in a Fabric game (see InjectorBiomeSourceMixin)
    private static final String NOISE_BIOME_INTERMEDIARY = "method_38109(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;";

    @WrapOperation(method = { NOISE_BIOME, NOISE_BIOME_INTERMEDIARY }, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/biome/Climate$Sampler;sample(III)Lnet/minecraft/world/level/biome/Climate$TargetPoint;"), require = 0)
    private Climate.TargetPoint ga2$gpuPoint(Climate.Sampler sampler, int x, int y, int z, Operation<Climate.TargetPoint> original) {
        return LithostitchedGpuBiomes.point(sampler, x, y, z, original);
    }

    @WrapOperation(method = { NOISE_BIOME, NOISE_BIOME_INTERMEDIARY }, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/biome/BiomeResolver;getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;"),
        require = 0)
    private Holder<Biome> ga2$gpuBase(BiomeResolver resolver, int x, int y, int z, Climate.Sampler sampler, Operation<Holder<Biome>> original) {
        return LithostitchedGpuBiomes.base(resolver, x, y, z, sampler, original);
    }
}
