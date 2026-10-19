package dev.denismasterherobrine.generatoraccelerator2.compat.biolith.mixin;

import dev.denismasterherobrine.generatoraccelerator2.compat.biolith.BiolithParameters;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Returns Biolith's built parameter list at the head of {@code parameters()}, without Biolith's per-call lock
 * ({@link BiolithParameters}). Applied late (priority 5000), so the inspection sees other mods' changes to the method
 * first ({@code biomes.parameters}).
 */
@Mixin(value = MultiNoiseBiomeSource.class, priority = 5000)
public abstract class MultiNoiseParametersMixin {
    @SuppressWarnings("unchecked")
    @Inject(method = "parameters", at = @At("HEAD"), cancellable = true)
    private void ga2$builtList(CallbackInfoReturnable<Climate.ParameterList<Holder<Biome>>> cir) {
        Object entries = BiolithParameters.entries((MultiNoiseBiomeSource) (Object) this);
        if (entries != null) {
            // The method's return value once built, passed through other mods' return modifiers (BiolithParameters.finisher)
            cir.setReturnValue((Climate.ParameterList<Holder<Biome>>) BiolithParameters.finish((MultiNoiseBiomeSource) (Object) this, entries));
        }
    }
}
