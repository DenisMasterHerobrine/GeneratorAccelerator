package dev.denismasterherobrine.generatoraccelerator2.mixin.features.predicate;

import dev.denismasterherobrine.generatoraccelerator2.features.predicate.PredicatePrograms;
import java.util.List;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.level.levelgen.blockpredicates.CombiningPredicate")
public interface CombiningPredicateAccessor extends PredicatePrograms.Combining {
    @Override
    @Accessor("predicates")
    List<BlockPredicate> ga2$predicates();
}
