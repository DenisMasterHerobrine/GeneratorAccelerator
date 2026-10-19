package dev.denismasterherobrine.generatoraccelerator2.mixin.structure;

import dev.denismasterherobrine.generatoraccelerator2.structure.FossilProbe;
import java.util.Optional;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.structures.NetherFossilStructure;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Answers empty before the column probe when no spot the probe can find has a valid biome ({@link FossilProbe}). */
@Mixin(value = NetherFossilStructure.class, priority = 5000)
public abstract class NetherFossilStructureMixin {
    @Shadow @Final public HeightProvider height;

    @Inject(method = "findGenerationPoint", at = @At("HEAD"), cancellable = true)
    private void ga2$skipWithoutBiome(Structure.GenerationContext context, CallbackInfoReturnable<Optional<Structure.GenerationStub>> cir) {
        if (FossilProbe.skip((Structure) (Object) this, this.height, context)) {
            cir.setReturnValue(Optional.empty());
        }
    }
}
