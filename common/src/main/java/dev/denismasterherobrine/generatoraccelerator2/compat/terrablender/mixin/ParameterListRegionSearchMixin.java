package dev.denismasterherobrine.generatoraccelerator2.compat.terrablender.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.biome.ClimateSearch;
import dev.denismasterherobrine.generatoraccelerator2.compat.terrablender.TerraBlenderMixins;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * TerraBlender's per-region biome search ({@code findValuePositional}, which TerraBlender's mixin adds to the parameter
 * list), run through GA2's exact flat climate search. Vanilla's {@code findValue} already goes through that search
 * ({@link TerraBlenderMixins}).
 * Applied after TerraBlender's mixin, and only with TerraBlender loaded.
 */
@Mixin(value = Climate.ParameterList.class, priority = 2000)
public abstract class ParameterListRegionSearchMixin {
    @Dynamic("findValuePositional is added by TerraBlender's MixinParameterList")
    @WrapOperation(method = "findValuePositional", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/biome/Climate$RTree;search(Lnet/minecraft/world/level/biome/Climate$TargetPoint;Lnet/minecraft/world/level/biome/Climate$DistanceMetric;)Ljava/lang/Object;"),
        require = 0)
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object ga2$flatRegionSearch(Climate.RTree<?> tree, Climate.TargetPoint target, Climate.DistanceMetric<?> metric, Operation<Object> original) {
        if (!TerraBlenderMixins.distanceSearch() || !ClimateSearch.enabled()) {
            return original.call(tree, target, metric);
        }
        if (TerraBlenderMixins.sampleVerify()) {
            // -Dgeneratoraccelerator2.verify.biomes=N: the tree walk from the same last result must give the same leaf
            ThreadLocal last = tree.lastResult;
            Object before = last.get();
            Object expected = original.call(tree, target, metric);
            Object expectedLast = last.get();
            last.set(before);
            Object actual = ClimateSearch.search(tree, target);
            // a quiet search (ClimateSearch.quietly) leaves the last result where it was
            TerraBlenderMixins.verified(expected == actual && (ClimateSearch.quiet() ? before : expectedLast) == last.get(), target);
            return actual;
        }
        return ClimateSearch.search(tree, target);
    }
}
