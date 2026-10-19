package dev.denismasterherobrine.generatoraccelerator2.compat.biolith.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.denismasterherobrine.generatoraccelerator2.compat.biolith.BiolithSearch;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * Runs Biolith's climate search through GA2's flat copy of the tree ({@link BiolithSearch}). The search method is
 * {@code biolith$searchTreeGet}, which Biolith's {@code MixinSearchTree} merges into the tree. Biolith's own search runs
 * whenever the flat copy does not fit. This mixin has a higher priority than Biolith's mixin, so it can wrap the merged
 * method. The result is Biolith's own record type, returned as an object, because GA2 does not compile against Biolith.
 * Only applied with Biolith loaded.
 */
@Mixin(value = Climate.RTree.class, priority = 2000)
public abstract class RTreeFittestMixin {
    @Dynamic("biolith$searchTreeGet, merged by Biolith's MixinSearchTree")
    @Coerce
    // Matched by name only. Biolith's method is not in the game's mappings, so a vanilla-typed descriptor would fail on Fabric.
    @WrapMethod(method = "biolith$searchTreeGet", require = 0)
    private Object ga2$flatFittest(Climate.TargetPoint target, Climate.DistanceMetric<?> metric, Operation<Object> original) {
        Climate.RTree<?> tree = (Climate.RTree<?>) (Object) this;
        Object[] before = BiolithSearch.sampleVerify() ? BiolithSearch.lastAnswers(tree) : null;
        Object fast = BiolithSearch.search(tree, target, metric);
        if (fast != null) {
            if (before != null) {
                // Starting from the same last answers, Biolith's search must give the same result and leave the same last answers
                Object[] after = BiolithSearch.lastAnswers(tree);
                BiolithSearch.restoreLastAnswers(tree, before);
                BiolithSearch.compare(fast, after, original.call(target, metric), BiolithSearch.lastAnswers(tree), target);
            }
            return fast;
        }
        BiolithSearch.BIOLITH.increment();
        return original.call(target, metric);
    }
}
