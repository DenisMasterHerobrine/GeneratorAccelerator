package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds nothing. Applied late, so the mixin plugin can record other mods' changes to the edge update that the tree transcription
 * runs itself ({@code StructureTemplate.updateShapeAtEdge} and the face walk of {@code DiscreteVoxelShape}). Hook {@code trees}.
 */
@Mixin(value = { StructureTemplate.class, DiscreteVoxelShape.class }, priority = 5000)
public abstract class TreeEdgeInspectMixin {
}
