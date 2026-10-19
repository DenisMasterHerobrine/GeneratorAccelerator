package dev.denismasterherobrine.generatoraccelerator2.mixin.features.predicate;

import dev.denismasterherobrine.generatoraccelerator2.features.predicate.PredicatePrograms;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.level.levelgen.blockpredicates.MatchingFluidsPredicate")
public interface MatchingFluidsPredicateAccessor extends PredicatePrograms.MatchingFluids {
    @Override
    @Accessor("fluids")
    HolderSet<Fluid> ga2$fluids();
}
