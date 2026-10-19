package dev.denismasterherobrine.generatoraccelerator2.mixin.dfc;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.MapAllMemo;
import java.util.IdentityHashMap;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** {@link MapAllMemo}: one coordinate function serves every nested point of a spline. */
@Mixin(DensityFunctions.Spline.Coordinate.class)
public abstract class SplineCoordinateMemoMixin {
    @WrapOperation(method = "mapAll", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/levelgen/DensityFunction;mapAll(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/DensityFunction;"))
    private DensityFunction ga2$memo(DensityFunction function, DensityFunction.Visitor visitor, Operation<DensityFunction> original) {
        IdentityHashMap<Object, DensityFunction> memo = MapAllMemo.current();
        if (memo == null) {
            return original.call(function, visitor);
        }
        DensityFunction mapped = memo.get(function);
        if (mapped == null) {
            mapped = original.call(function, visitor);
            memo.put(function, mapped);
        }
        return mapped;
    }
}
