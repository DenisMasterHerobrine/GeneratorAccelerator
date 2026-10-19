package dev.denismasterherobrine.generatoraccelerator2.mixin.features.predicate;

import net.minecraft.world.level.levelgen.blockpredicates.HasSturdyFacePredicate;
import net.minecraft.world.level.levelgen.blockpredicates.InsideWorldBoundsPredicate;
import net.minecraft.world.level.levelgen.blockpredicates.MatchingBlockTagPredicate;
import net.minecraft.world.level.levelgen.blockpredicates.SolidPredicate;
import net.minecraft.world.level.levelgen.blockpredicates.StateTestingPredicate;
import net.minecraft.world.level.levelgen.blockpredicates.WouldSurvivePredicate;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds nothing. Applied late so the mixin plugin can record, per class, other mods' changes to the vanilla block predicates
 * that the predicate programs run themselves ({@code blockPredicate.<class>}). A changed class is called through its own test.
 */
@Mixin(value = { StateTestingPredicate.class, MatchingBlockTagPredicate.class, SolidPredicate.class, WouldSurvivePredicate.class,
    InsideWorldBoundsPredicate.class, HasSturdyFacePredicate.class }, targets = {
    "net.minecraft.world.level.levelgen.blockpredicates.AllOfPredicate", "net.minecraft.world.level.levelgen.blockpredicates.AnyOfPredicate",
    "net.minecraft.world.level.levelgen.blockpredicates.NotPredicate", "net.minecraft.world.level.levelgen.blockpredicates.TrueBlockPredicate",
    "net.minecraft.world.level.levelgen.blockpredicates.MatchingBlocksPredicate", "net.minecraft.world.level.levelgen.blockpredicates.MatchingFluidsPredicate",
    "net.minecraft.world.level.levelgen.blockpredicates.ReplaceablePredicate" }, priority = 5000)
public abstract class BlockPredicatesInspectMixin {
}
