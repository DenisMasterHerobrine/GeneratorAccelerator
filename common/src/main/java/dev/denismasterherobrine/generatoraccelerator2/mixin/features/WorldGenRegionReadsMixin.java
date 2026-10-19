package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import dev.denismasterherobrine.generatoraccelerator2.features.region.Ga2Region;
import dev.denismasterherobrine.generatoraccelerator2.features.region.RegionReads;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The region's block and fluid reads through {@link RegionReads}. Applied late (priority 5000), so the mixin plugin sees
 * other mods' changes to the two methods before deciding whether the reads may be used ({@code regionReads}). The plugin
 * also sees changes to {@code getChunk(int, int)}, which the reads do not call.
 */
@Mixin(value = WorldGenRegion.class, priority = 5000)
public abstract class WorldGenRegionReadsMixin {
    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void ga2$read(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        RegionReads reads = ((Ga2Region) this).ga2$reads();
        if (reads != null) {
            BlockState state = reads.read(pos);
            if (state != null) {
                cir.setReturnValue(state);
            }
        }
    }

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void ga2$readFluid(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
        RegionReads reads = ((Ga2Region) this).ga2$reads();
        if (reads != null) {
            FluidState state = reads.readFluid(pos);
            if (state != null) {
                cir.setReturnValue(state);
            }
        }
    }
}
