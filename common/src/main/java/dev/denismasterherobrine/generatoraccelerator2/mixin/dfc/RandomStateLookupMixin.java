package dev.denismasterherobrine.generatoraccelerator2.mixin.dfc;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The noise and positional random memos of {@code RandomState} look the key up with {@code ConcurrentHashMap.computeIfAbsent}.
 * That call locks the bin of the key whenever the key is not the first node of the bin, even when the value is present.
 * Surface rules that run through their own objects ask for their noises on every chunk, from every surface thread at once.
 * In a 76-mod pack, the workers spent 73 s waiting on those locks in a three-minute run. A plain {@code get} first returns
 * the same object without a lock. A key that is not yet present still goes through {@code computeIfAbsent}, so exactly one
 * value is ever made per key.
 */
@Mixin(RandomState.class)
public abstract class RandomStateLookupMixin {
    @WrapOperation(method = { "getOrCreateNoise", "getOrCreateRandomFactory" }, at = @At(value = "INVOKE",
        target = "Ljava/util/Map;computeIfAbsent(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;"), require = 0)
    private Object ga2$presentFirst(Map<Object, Object> map, Object key, Function<Object, Object> make, Operation<Object> original) {
        Object value = map.get(key);
        return value != null ? value : original.call(map, key, make);
    }
}
