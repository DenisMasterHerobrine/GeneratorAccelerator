package dev.denismasterherobrine.generatoraccelerator2.mixin.features.predicate;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.features.placement.ScanPlacement;
import dev.denismasterherobrine.generatoraccelerator2.features.predicate.PredicatePrograms;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
public abstract class EnvironmentScanPredicateMixin implements ScanPlacement {
    @Unique
    private static final FeatureTargets.Hook GA2$CALLS = FeatureTargets.hook(FeatureTargets.PREDICATE_CALLS);

    @Shadow @Final private BlockPredicate targetCondition;
    @Shadow @Final private BlockPredicate allowedSearchCondition;
    @Shadow @Final private Direction directionOfSearch;
    @Shadow @Final private int maxSteps;

    @Unique
    private Object ga2$targetProgram;
    @Unique
    private Object ga2$allowedProgram;

    /** 1 when the allowed condition is vanilla's {@code true} predicate (the default), 2 when not, 0 before it was looked at. */
    @Unique
    private byte ga2$allowedAlways;

    /**
     * {@code getPositions} without its stream. Its tests go through the programs as the wrapped calls below do. The default allowed
     * condition answers true and reads nothing, so it is not asked.
     */
    @Override
    public boolean ga2$scan(WorldGenLevel level, BlockPos.MutableBlockPos pos) {
        byte always = this.ga2$allowedAlways;
        if (always == 0) {
            this.ga2$allowedAlways = always = PredicatePrograms.alwaysTrue(this.allowedSearchCondition) ? (byte) 1 : (byte) 2;
        }
        boolean ask = always != 1;
        if (ask && !this.ga2$test(this.allowedSearchCondition, level, pos)) {
            return false;
        }
        for (int i = 0; i < this.maxSteps; i++) {
            if (this.ga2$test(this.targetCondition, level, pos)) {
                return true;
            }
            pos.move(this.directionOfSearch);
            if (level.isOutsideBuildHeight(pos.getY())) {
                return false;
            }
            if (ask && !this.ga2$test(this.allowedSearchCondition, level, pos)) {
                break;
            }
        }
        return this.ga2$test(this.targetCondition, level, pos);
    }

    @Override
    public boolean ga2$readsOnly() {
        return PredicatePrograms.readsOnly(this.targetCondition) && PredicatePrograms.readsOnly(this.allowedSearchCondition);
    }

    @Unique
    private boolean ga2$test(BlockPredicate predicate, WorldGenLevel level, BlockPos pos) {
        if (!GA2$CALLS.allowed()) {
            return predicate.test(level, pos);
        }
        Object program;
        if (predicate == this.targetCondition) {
            program = this.ga2$targetProgram;
            if (program == null) {
                program = PredicatePrograms.programFor(predicate);
                this.ga2$targetProgram = program;
            }
        } else {
            program = this.ga2$allowedProgram;
            if (program == null) {
                program = PredicatePrograms.programFor(predicate);
                this.ga2$allowedProgram = program;
            }
        }
        return PredicatePrograms.test(program, predicate, level, pos);
    }

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
