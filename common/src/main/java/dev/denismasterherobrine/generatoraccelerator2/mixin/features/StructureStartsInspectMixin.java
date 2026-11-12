package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import net.minecraft.world.level.StructureManager;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds nothing. Applied late so the mixin plugin can record other mods' changes to {@code StructureManager.startsForStructure}.
 * The decoration answers an unreferenced structure without that method ({@code StructureStartsLookupMixin}).
 */
@Mixin(value = StructureManager.class, priority = 5000)
public abstract class StructureStartsInspectMixin {
}
