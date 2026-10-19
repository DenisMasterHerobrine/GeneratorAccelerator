package dev.denismasterherobrine.generatoraccelerator2.mixin.features.predicate;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.features.predicate.PredicatePrograms;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.placement.EnvironmentScanPlacement;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** {@code environment_scan} tests its two predicates at every step through {@link PredicatePrograms}. The programs are kept in scan fields. */
@Mixin(value = EnvironmentScanPlacement.class, priority = 5000)
public abstract class EnvironmentScanPredicateMixin {
    @Unique
    private static final FeatureTargets.Hook GA2$CALLS = FeatureTargets.hook(FeatureTargets.PREDICATE_CALLS);

    @Shadow @Final private BlockPredicate targetCondition;
    @Shadow @Final private BlockPredicate allowedSearchCondition;

    @Unique
    private Object ga2$targetProgram;
    @Unique
    private Object ga2$allowedProgram;

    @WrapOperation(method = "getPositions", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/levelgen/blockpredicates/BlockPredicate;test(Ljava/lang/Object;Ljava/lang/Object;)Z"))
    private boolean ga2$program(BlockPredicate predicate, Object level, Object pos, Operation<Boolean> original) {
        if (GA2$CALLS.allowed() && level instanceof WorldGenLevel l && pos instanceof BlockPos p) {
            Object program;
            if (predicate == this.targetCondition) {
                program = this.ga2$targetProgram;
                if (program == null) {
                    program = PredicatePrograms.programFor(predicate);
                    this.ga2$targetProgram = program;
                }
            } else if (predicate == this.allowedSearchCondition) {
                program = this.ga2$allowedProgram;
                if (program == null) {
                    program = PredicatePrograms.programFor(predicate);
                    this.ga2$allowedProgram = program;
                }
            } else {
                return original.call(predicate, level, pos);
            }
            return PredicatePrograms.test(program, predicate, l, p);
        }
        return original.call(predicate, level, pos);
    }
}
