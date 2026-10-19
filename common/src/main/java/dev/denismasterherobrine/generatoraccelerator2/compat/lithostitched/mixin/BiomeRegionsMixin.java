package dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched.LithostitchedRegions;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The region function of Lithostitched's biome injector regions, computed once per quart column when it provably does
 * not read y ({@link LithostitchedRegions}). Only applied with Lithostitched loaded. A Lithostitched version without
 * this call is left as it is.
 */
@Mixin(targets = "dev.worldgen.lithostitched.impl.worldgen.biomeinjector.region.RegionManager$BiomeRegions")
public abstract class BiomeRegionsMixin {
    @WrapOperation(method = "getRegionValue(Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)I",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/DensityFunction;compute(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D"),
        require = 0)
    private double ga2$columnValue(DensityFunction function, DensityFunction.FunctionContext context, Operation<Double> original) {
        return LithostitchedRegions.value(function, context, original);
    }
}
