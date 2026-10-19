package dev.denismasterherobrine.generatoraccelerator2.mixin.light;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.light.FastLight;
import dev.denismasterherobrine.generatoraccelerator2.light.LightSources;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Finds a new chunk's block light sources from the chunk's section palettes. See {@link LightSources}. */
@Mixin(value = ChunkAccess.class, priority = 5000)
public abstract class ChunkAccessLightMixin {
    @Inject(method = "findBlockLightSources", at = @At("HEAD"), cancellable = true)
    private void ga2$findBlockLightSources(BiConsumer<BlockPos, BlockState> consumer, CallbackInfo ci) {
        ChunkAccess self = (ChunkAccess) (Object) this;
        if (FastLight.on && HookTargets.allowed(HookTargets.LIGHT_SOURCES) && LightSources.handles(self)) {
            LightSources.find(self, consumer);
            ci.cancel();
        }
    }
}
