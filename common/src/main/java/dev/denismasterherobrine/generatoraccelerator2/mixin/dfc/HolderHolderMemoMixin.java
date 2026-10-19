package dev.denismasterherobrine.generatoraccelerator2.mixin.dfc;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.MapAllMemo;
import java.util.IdentityHashMap;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.spongepowered.asm.mixin.Mixin;

/** {@link MapAllMemo}: every use of the same registry holder maps to the same function. */
@Mixin(DensityFunctions.HolderHolder.class)
public abstract class HolderHolderMemoMixin {
    @WrapMethod(method = "mapAll")
    private DensityFunction ga2$memo(DensityFunction.Visitor visitor, Operation<DensityFunction> original) {
        IdentityHashMap<Object, DensityFunction> memo = MapAllMemo.current();
        if (memo == null) {
            return original.call(visitor);
        }
        Object key = ((DensityFunctions.HolderHolder) (Object) this).function();
        DensityFunction mapped = memo.get(key);
        if (mapped == null) {
            mapped = original.call(visitor);
            memo.put(key, mapped);
        }
        return mapped;
    }
}
