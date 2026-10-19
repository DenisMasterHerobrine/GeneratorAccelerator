package dev.denismasterherobrine.generatoraccelerator2.compat.biomeswevegone.mixin;

import dev.denismasterherobrine.generatoraccelerator2.compat.biomeswevegone.BiomesWeveGoneBands;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Oh The Biomes We've Gone keeps the band tables of its bands rule in a plain hash map on the surface system. The mod
 * fills the map on first use. GA2 builds surfaces on several threads at once. GA2 therefore replaces the map with a
 * concurrent one once the surface system is constructed, after the mod's own field initializer. Only applied with the
 * mod loaded.
 */
@Mixin(value = SurfaceSystem.class, priority = 2000)
public abstract class SurfaceSystemBandsMixin {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void ga2$concurrentBandTables(CallbackInfo ci) {
        BiomesWeveGoneBands.makeConcurrent((SurfaceSystem) (Object) this);
    }
}
