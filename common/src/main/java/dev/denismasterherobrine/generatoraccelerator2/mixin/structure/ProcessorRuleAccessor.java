package dev.denismasterherobrine.generatoraccelerator2.mixin.structure;

import net.minecraft.world.level.levelgen.structure.templatesystem.PosRuleTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.ProcessorRule;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.rule.blockentity.RuleBlockEntityModifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** What a processor rule hands the per-block random source to ({@code RuleProcessorRandomMixin}). */
@Mixin(ProcessorRule.class)
public interface ProcessorRuleAccessor {
    @Accessor("inputPredicate")
    RuleTest ga2$inputPredicate();

    @Accessor("locPredicate")
    RuleTest ga2$locPredicate();

    @Accessor("posPredicate")
    PosRuleTest ga2$posPredicate();

    @Accessor("blockEntityModifier")
    RuleBlockEntityModifier ga2$blockEntityModifier();
}
