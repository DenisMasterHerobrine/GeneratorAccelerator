package dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched.LithostitchedInjectors;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lithostitched's injector source looks up each kind of injector before walking it. For partial replacements, the walk is
 * handed only the replacements whose targets hold the current biome ({@link LithostitchedInjectors}). The current biome is
 * the method's {@code biome} local. Only the base biome lookup and an alternate layout's lookup assign that local. The mixin
 * plugin checks this in the bytecode ({@code LithostitchedMixins}). Both lookup results are noted per thread as they are
 * made. A wrapper around each call of the method keeps the caller's note. Applied only with Lithostitched loaded and with
 * that method shape.
 */
@Mixin(targets = "dev.worldgen.lithostitched.impl.worldgen.biomeinjector.internal.InjectorBiomeSource")
public abstract class InjectorBiomeSourceMixin {
    private static final String NOISE_BIOME = "getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;";
    @WrapMethod(method = NOISE_BIOME, require = 0)
    private Holder<Biome> ga2$keepCallerBiome(int x, int y, int z, Climate.Sampler sampler, Operation<Holder<Biome>> original) {
        Holder<?> caller = LithostitchedInjectors.currentBiome();
        try {
            return original.call(x, y, z, sampler);
        } finally {
            LithostitchedInjectors.noteBiome(caller);
        }
    }

    // The results are only noted, with no wrapper around the calls. A wrapped call boxes its arguments on every biome lookup.
    @ModifyExpressionValue(method = NOISE_BIOME, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/biome/BiomeResolver;getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;"),
        require = 0)
    private Holder<Biome> ga2$noteBase(Holder<Biome> biome) {
        LithostitchedInjectors.noteBiome(biome);
        return biome;
    }

    @ModifyExpressionValue(method = NOISE_BIOME, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/biome/Climate$ParameterList;findValue(Lnet/minecraft/world/level/biome/Climate$TargetPoint;)Ljava/lang/Object;"),
        require = 0)
    private Object ga2$noteAlternate(Object biome) {
        LithostitchedInjectors.noteBiome(biome instanceof Holder<?> h ? h : null);
        return biome;
    }

    @WrapOperation(method = NOISE_BIOME, at = @At(value = "INVOKE", target = "Ljava/util/Map;getOrDefault(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),
        require = 0)
    private Object ga2$injectorsFor(Map<?, ?> map, Object type, Object fallback, Operation<Object> original) {
        return LithostitchedInjectors.candidates(type, original.call(map, type, fallback));
    }
}
