package dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched.LithostitchedInjectors;
import java.util.HashMap;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * {@code -Dgeneratoraccelerator2.verify.injectors=true}: the biome each partial replacement is asked about is the noted one
 * ({@link InjectorBiomeSourceMixin}). Applied only with that switch, since the wrapper boxes its arguments on every call.
 */
@Mixin(targets = "dev.worldgen.lithostitched.impl.worldgen.biomeinjector.internal.InjectorBiomeSource")
public abstract class InjectorBiomeSourceVerifyMixin {
    private static final String NOISE_BIOME = "getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;";

    @WrapOperation(method = NOISE_BIOME, at = @At(value = "INVOKE",
        target = "Ldev/worldgen/lithostitched/impl/worldgen/biomeinjector/ReplacePartially;matches(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;Lnet/minecraft/world/level/biome/Climate$TargetPoint;Ljava/util/HashMap;Lnet/minecraft/core/Holder;Lnet/minecraft/resources/ResourceKey;)Z"),
        require = 0)
    private boolean ga2$checkNotedBiome(@Coerce Object injector, DensityFunction.FunctionContext context, Climate.TargetPoint target, HashMap<?, ?> densities,
                                        Holder<?> biome, ResourceKey<?> region, Operation<Boolean> original) {
        LithostitchedInjectors.checkNoted(biome);
        return original.call(injector, context, target, densities, biome, region);
    }
}
