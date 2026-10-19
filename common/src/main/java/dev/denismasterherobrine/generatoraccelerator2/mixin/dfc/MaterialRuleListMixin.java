package dev.denismasterherobrine.generatoraccelerator2.mixin.dfc;

import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.DfcHooks;
import java.util.List;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.material.MaterialRuleList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The noise fill asks the material rules for every block. Vanilla walks the rule list with a new
 * iterator each time. This walks it by index, with the same rules, order and first non-null answer.
 */
@Mixin(value = MaterialRuleList.class, priority = 5000)
public abstract class MaterialRuleListMixin {
    @Inject(method = "calculate", at = @At("HEAD"), cancellable = true)
    private void ga2$byIndex(DensityFunction.FunctionContext context, CallbackInfoReturnable<BlockState> cir) {
        if (!DfcHooks.MATERIAL_RULES.allowed()) {
            // another mod changes this method after this point (it may observe or change the answer)
            return;
        }
        List<NoiseChunk.BlockStateFiller> rules = ((MaterialRuleList) (Object) this).materialRuleList();
        BlockState state = null;
        for (int i = 0, n = rules.size(); i < n; i++) {
            state = rules.get(i).calculate(context);
            if (state != null) {
                break;
            }
        }
        cir.setReturnValue(state);
    }
}
