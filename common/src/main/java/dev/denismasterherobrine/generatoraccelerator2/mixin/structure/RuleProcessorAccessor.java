package dev.denismasterherobrine.generatoraccelerator2.mixin.structure;

import com.google.common.collect.ImmutableList;
import net.minecraft.world.level.levelgen.structure.templatesystem.ProcessorRule;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleProcessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The rules the template clip's capped pass reads to tell which blocks a rule can change ({@code structure.CappedPlacement}). */
@Mixin(RuleProcessor.class)
public interface RuleProcessorAccessor {
    @Accessor("rules")
    ImmutableList<ProcessorRule> ga2$rules();
}
