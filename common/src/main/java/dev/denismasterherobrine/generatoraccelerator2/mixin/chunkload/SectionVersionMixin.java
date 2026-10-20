package dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload;

import dev.denismasterherobrine.generatoraccelerator2.chunkload.SectionVersion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Counts a section's block and biome changes ({@link SectionVersion}). The count is read by the thread that changes the
 * section, or after it handed the section to another thread.
 */
@Mixin(LevelChunkSection.class)
public abstract class SectionVersionMixin implements SectionVersion {
    @Unique
    private long ga2$version;

    @Override
    public long ga2$version() {
        return this.ga2$version;
    }

    // Targets vanilla's method and GA2's body for it ({@code SectionWriteMixin}). GA2's body replaces vanilla's once the class is
    // complete. GA2's method is targeted by name only. It is not in the game's mappings, so a descriptor naming vanilla types would
    // not match on a Fabric game.
    @Inject(method = { "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
        "ga2$setBlockState" }, at = @At("HEAD"))
    private void ga2$blockChange(int x, int y, int z, BlockState state, boolean lock, CallbackInfoReturnable<BlockState> cir) {
        this.ga2$version++;
    }

    @Inject(method = "fillBiomesFromNoise", at = @At("HEAD"))
    private void ga2$biomeChange(CallbackInfo ci) {
        this.ga2$version++;
    }
}
