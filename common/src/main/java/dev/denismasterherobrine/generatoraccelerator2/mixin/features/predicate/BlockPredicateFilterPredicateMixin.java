package dev.denismasterherobrine.generatoraccelerator2.mixin.features.predicate;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.features.predicate.PredicatePrograms;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.placement.BlockPredicateFilter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@code block_predicate_filter} tests its predicate through {@link PredicatePrograms}. The predicate's program is kept in a
 * field of the filter. The placement pipeline tests the predicate the same way ({@link PredicatePrograms.FilterProgram}).
 */
@Mixin(value = BlockPredicateFilter.class, priority = 5000)
public abstract class BlockPredicateFilterPredicateMixin implements PredicatePrograms.FilterProgram {
    @Unique
    private static final FeatureTargets.Hook GA2$CALLS = FeatureTargets.hook(FeatureTargets.PREDICATE_CALLS);

    @Shadow @Final private BlockPredicate predicate;

    @Unique
    private Object ga2$program;

    @Override
    public boolean ga2$test(WorldGenLevel level, BlockPos pos) {
        Object program = this.ga2$program;
        if (program == null) {
            program = PredicatePrograms.programFor(this.predicate);
            this.ga2$program = program;
        }
        return PredicatePrograms.test(program, this.predicate, level, pos);
    }

    @Override
    public boolean ga2$readsOnly() {
        return PredicatePrograms.readsOnly(this.predicate);
    }

    @WrapOperation(method = "shouldPlace", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/levelgen/blockpredicates/BlockPredicate;test(Ljava/lang/Object;Ljava/lang/Object;)Z"))
    private boolean ga2$program(BlockPredicate predicate, Object level, Object pos, Operation<Boolean> original) {
        if (predicate == this.predicate && GA2$CALLS.allowed() && level instanceof WorldGenLevel l && pos instanceof BlockPos p) {
            return this.ga2$test(l, p);
        }
        return original.call(predicate, level, pos);
    }
}
