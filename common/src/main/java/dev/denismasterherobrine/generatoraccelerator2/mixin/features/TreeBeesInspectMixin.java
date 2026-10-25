package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import net.minecraft.world.level.levelgen.feature.treedecorators.BeehiveDecorator;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds nothing. Applied late, so the mixin plugin can record other mods' changes to the beehive decorator that the tree
 * transcription places itself ({@code features.tree.TreeBees}, hook {@code treeBees}). After such a change the decorator runs as
 * before, with the tree's leaves in a set.
 */
@Mixin(value = BeehiveDecorator.class, priority = 5000)
public abstract class TreeBeesInspectMixin {
}
