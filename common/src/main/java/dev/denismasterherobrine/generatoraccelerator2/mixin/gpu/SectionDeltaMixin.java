package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.denismasterherobrine.generatoraccelerator2.gpu.decor.DeltaLog;
import dev.denismasterherobrine.generatoraccelerator2.gpu.decor.Ga2DeltaSection;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Every block write into a section of a chunk the GPU keeps a copy of goes to the chunk's {@link DeltaLog}. Every writer in worldgen,
 * GA2's and vanilla's, ends here: {@code ProtoChunk.setBlockState}, {@code BulkSectionAccess}, GA2's region and section writers.
 * Applied only with {@code -Dgeneratoraccelerator2.gpu.decoration=true} ({@code DfcMixinPlugin}).
 */
@Mixin(LevelChunkSection.class)
public abstract class SectionDeltaMixin implements Ga2DeltaSection {
    @Unique
    private DeltaLog ga2$log;
    @Unique
    private int ga2$index;

    @Override
    public void ga2$track(DeltaLog log, int index) {
        this.ga2$log = log;
        this.ga2$index = index;
    }

    // Targets vanilla's method and GA2's body for it ({@code SectionWriteMixin}). GA2's body replaces vanilla's once the class is
    // complete. GA2's method is targeted by name only. It is not in the game's mappings, so a descriptor naming vanilla types would
    // not match on a Fabric game.
    @ModifyReturnValue(method = { "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
        "ga2$setBlockState" }, at = @At("RETURN"))
    private BlockState ga2$record(BlockState old, @Local(argsOnly = true, ordinal = 0) int x, @Local(argsOnly = true, ordinal = 1) int y,
                                  @Local(argsOnly = true, ordinal = 2) int z, @Local(argsOnly = true) BlockState state) {
        DeltaLog log = this.ga2$log;
        if (log != null && old != state) {
            log.record(this.ga2$index, x, y, z, state, old);
        }
        return old;
    }
}
