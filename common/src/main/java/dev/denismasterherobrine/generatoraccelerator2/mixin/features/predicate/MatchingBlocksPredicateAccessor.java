package dev.denismasterherobrine.generatoraccelerator2.mixin.features.predicate;

import dev.denismasterherobrine.generatoraccelerator2.features.predicate.PredicatePrograms;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.level.levelgen.blockpredicates.MatchingBlocksPredicate")
public interface MatchingBlocksPredicateAccessor extends PredicatePrograms.MatchingBlocks {
    @Override
    @Accessor("blocks")
    HolderSet<Block> ga2$blocks();
}
