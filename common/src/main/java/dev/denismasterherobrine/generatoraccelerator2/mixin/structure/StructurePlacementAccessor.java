package dev.denismasterherobrine.generatoraccelerator2.mixin.structure;

import java.util.Optional;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A placement's exclusion zone, which makes its check ask another set's placement about nearby chunks ({@code RingBounds}). */
@Mixin(StructurePlacement.class)
public interface StructurePlacementAccessor {
    @Accessor("exclusionZone")
    Optional<StructurePlacement.ExclusionZone> ga2$exclusionZone();
}
