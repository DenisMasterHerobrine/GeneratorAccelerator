package dev.denismasterherobrine.generatoraccelerator2.compat.gtceu.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.compat.gtceu.ConcurrentVeinMap;
import it.unimi.dsi.fastutil.objects.Object2IntFunction;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.HashMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * GTCEu's bedrock fluid veins, safe to fill from the worldgen threads while the server thread saves them. TerraFirmaGreg's spout
 * feature and fluid vein placement ask for a chunk's vein during decoration, which adds it to the map. The save iterated the map
 * at the same time and stopped the server with a {@code ConcurrentModificationException}. The map becomes a {@link ConcurrentVeinMap}
 * where the constructor makes it, and the per-biome weight cache is filled under its own lock. A vein is the same whichever thread
 * makes it first: it comes from the world's seed and the chunk. Without a match nothing changes.
 */
@Mixin(targets = "com.gregtechceu.gtceu.api.data.worldgen.bedrockfluid.BedrockFluidVeinSavedData", remap = false)
public abstract class BedrockFluidVeinSavedDataMixin {
    @ModifyExpressionValue(method = "<init>", at = @At(value = "NEW", target = "java/util/HashMap"), require = 0)
    private HashMap<Object, Object> ga2$concurrentVeins(HashMap<Object, Object> veins) {
        return new ConcurrentVeinMap<>(veins);
    }

    @WrapOperation(method = "getTotalWeight", at = @At(value = "INVOKE",
        target = "Lit/unimi/dsi/fastutil/objects/Object2IntMap;computeIfAbsent(Ljava/lang/Object;Lit/unimi/dsi/fastutil/objects/Object2IntFunction;)I"),
        require = 0)
    private int ga2$lockedWeights(Object2IntMap<Object> weights, Object biome, Object2IntFunction<Object> weigh, Operation<Integer> original) {
        synchronized (weights) {
            return original.call(weights, biome, weigh);
        }
    }
}
