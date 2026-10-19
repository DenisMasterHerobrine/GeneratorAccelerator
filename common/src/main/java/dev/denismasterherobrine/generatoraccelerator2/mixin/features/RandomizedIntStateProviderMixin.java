package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.feature.stateproviders.RandomizedIntStateProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code getState} with the cached property read once. The provider belongs to a shared configured feature.
 * Vanilla reads the field again after checking it. With parallel decoration, another thread could swap in a
 * property the state does not have between the check and the second read. Same random calls, same result.
 */
@Mixin(RandomizedIntStateProvider.class)
public abstract class RandomizedIntStateProviderMixin {
    @Shadow @Final private BlockStateProvider source;
    @Shadow @Final private String propertyName;
    @Shadow @Nullable private IntegerProperty property;
    @Shadow @Final private IntProvider values;

    @Shadow
    @Nullable
    private static IntegerProperty findProperty(BlockState state, String name) {
        throw new AssertionError();
    }

    @Inject(method = "getState", at = @At("HEAD"), cancellable = true)
    private void ga2$readOnce(RandomSource random, BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        BlockState state = this.source.getState(random, pos);
        IntegerProperty cached = this.property;
        if (cached == null || !state.hasProperty(cached)) {
            IntegerProperty found = findProperty(state, this.propertyName);
            if (found == null) {
                cir.setReturnValue(state);
                return;
            }
            this.property = found;
            cached = found;
        }
        cir.setReturnValue(state.setValue(cached, Integer.valueOf(this.values.sample(random))));
    }
}
