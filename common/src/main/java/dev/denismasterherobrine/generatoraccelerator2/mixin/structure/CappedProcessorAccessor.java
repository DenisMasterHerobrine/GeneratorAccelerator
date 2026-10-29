package dev.denismasterherobrine.generatoraccelerator2.mixin.structure;

import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.levelgen.structure.templatesystem.CappedProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * What the template clip's capped pass reads ({@code structure.CappedPlacement}). Applied late, so the mixin plugin also records
 * other mods' changes to the finalize pass it transcribes (hook {@code structures.templateCapped}).
 */
@Mixin(value = CappedProcessor.class, priority = 5000)
public interface CappedProcessorAccessor {
    @Accessor("delegate")
    StructureProcessor ga2$delegate();

    @Accessor("limit")
    IntProvider ga2$limit();
}
