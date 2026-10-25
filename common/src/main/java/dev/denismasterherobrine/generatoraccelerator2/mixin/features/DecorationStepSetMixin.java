package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.features.StepFeatureSet;
import it.unimi.dsi.fastutil.ints.IntArraySet;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Each decoration step's feature indexes go into a hash set instead of vanilla's array set ({@link StepFeatureSet}). The rest of
 * the step's loop runs as it is. Hook {@code decorationSteps}: another mod's wrapper of the same set keeps vanilla's.
 */
@Mixin(ChunkGenerator.class)
public abstract class DecorationStepSetMixin {
    @Unique
    private static final FeatureTargets.Hook GA2_HOOK = FeatureTargets.hook("decorationSteps");

    @WrapOperation(method = "applyBiomeDecoration", at = @At(value = "NEW", target = "()Lit/unimi/dsi/fastutil/ints/IntArraySet;"), require = 0)
    private IntArraySet ga2$stepSet(Operation<IntArraySet> original) {
        return GA2_HOOK.allowed() ? new StepFeatureSet() : original.call();
    }
}
