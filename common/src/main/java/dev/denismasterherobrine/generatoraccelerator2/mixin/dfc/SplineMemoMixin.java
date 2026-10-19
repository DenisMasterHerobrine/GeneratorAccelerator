package dev.denismasterherobrine.generatoraccelerator2.mixin.dfc;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.MapAllMemo;
import java.util.IdentityHashMap;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.spongepowered.asm.mixin.Mixin;

/** {@link MapAllMemo}: a spline used in several places of the router. */
@Mixin(DensityFunctions.Spline.class)
public abstract class SplineMemoMixin {
    @WrapMethod(method = "mapAll")
    private DensityFunction ga2$memo(DensityFunction.Visitor visitor, Operation<DensityFunction> original) {
        IdentityHashMap<Object, DensityFunction> memo = MapAllMemo.current();
        if (memo == null) {
            return original.call(visitor);
        }
        DensityFunction mapped = memo.get(this);
        if (mapped == null) {
            mapped = original.call(visitor);
            memo.put(this, mapped);
        }
        return mapped;
    }
}
