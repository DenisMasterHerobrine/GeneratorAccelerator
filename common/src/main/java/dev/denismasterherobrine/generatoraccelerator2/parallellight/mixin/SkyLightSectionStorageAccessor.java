package dev.denismasterherobrine.generatoraccelerator2.parallellight.mixin;

import net.minecraft.world.level.lighting.SkyLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** {@code hasLightDataAtOrBelow}, which the sky light's empty-section handling asks. */
@Mixin(SkyLightSectionStorage.class)
public interface SkyLightSectionStorageAccessor {
    @Invoker("hasLightDataAtOrBelow")
    boolean ga2pl$hasLightDataAtOrBelow(int sectionY);
}
