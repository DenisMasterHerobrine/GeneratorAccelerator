package dev.denismasterherobrine.generatoraccelerator2.mixin.structure;

import dev.denismasterherobrine.generatoraccelerator2.structure.RingBounds;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Answers false without the ring positions for a chunk no ring position can be ({@link RingBounds}). */
@Mixin(value = ConcentricRingsStructurePlacement.class, priority = 5000)
public abstract class ConcentricRingsStructurePlacementMixin {
    @Inject(method = "isPlacementChunk", at = @At("HEAD"), cancellable = true)
    private void ga2$insideInnerRing(ChunkGeneratorStructureState state, int x, int z, CallbackInfoReturnable<Boolean> cir) {
        if (RingBounds.never(state, (ConcentricRingsStructurePlacement) (Object) this, x, z)) {
            cir.setReturnValue(false);
        }
    }
}
