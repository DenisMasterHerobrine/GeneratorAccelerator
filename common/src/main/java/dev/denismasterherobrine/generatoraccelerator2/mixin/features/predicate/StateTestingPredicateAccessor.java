package dev.denismasterherobrine.generatoraccelerator2.mixin.features.predicate;

import dev.denismasterherobrine.generatoraccelerator2.features.predicate.PredicatePrograms;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.blockpredicates.StateTestingPredicate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(StateTestingPredicate.class)
public interface StateTestingPredicateAccessor extends PredicatePrograms.StateTesting {
    @Override
    @Accessor("offset")
    Vec3i ga2$offset();
}
