package dev.denismasterherobrine.generatoraccelerator2.mixin.features.predicate;

import dev.denismasterherobrine.generatoraccelerator2.features.predicate.PredicatePrograms;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.blockpredicates.MatchingBlockTagPredicate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MatchingBlockTagPredicate.class)
public interface MatchingBlockTagPredicateAccessor extends PredicatePrograms.MatchingTag {
    @Override
    @Accessor("tag")
    TagKey<Block> ga2$tag();
}
