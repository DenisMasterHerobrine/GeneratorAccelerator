package dev.denismasterherobrine.generatoraccelerator2.mixin.dfc;

import net.minecraft.world.level.levelgen.material.MaterialRuleList;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds nothing. Applied late so the mixin plugin can record other mods' changes to {@code MaterialRuleList.calculate}, which the GPU
 * fill answers itself ({@code noise.materialRules}). Vanilla walks its rule array by index.
 */
@Mixin(value = MaterialRuleList.class, priority = 5000)
public abstract class MaterialRuleListMixin {
}
