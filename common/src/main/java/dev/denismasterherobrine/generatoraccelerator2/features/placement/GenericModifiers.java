package dev.denismasterherobrine.generatoraccelerator2.features.placement;

import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.RepeatingPlacement;

/**
 * Which placement modifiers the pipeline may still run without their streams, among classes it does not transcribe. Every
 * {@code PlacementFilter} qualifies, because vanilla makes its {@code getPositions} final
 * ({@code shouldPlace(...) ? pos : nothing}). Every {@code RepeatingPlacement} that keeps vanilla's {@code getPositions}
 * qualifies ({@code count(...)} times the same position). Each kind stands down when another mod changes that
 * {@code getPositions}.
 */
public final class GenericModifiers {
    private GenericModifiers() {}

    /** {@code getPositions}'s name in the running game ({@link Names}). */
    private static final String GET_POSITIONS = Names.runtimeMethod("net/minecraft/world/level/levelgen/placement/RepeatingPlacement", "getPositions",
        "(Lnet/minecraft/world/level/levelgen/placement/PlacementContext;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Ljava/util/stream/Stream;");

    private static final ClassValue<Boolean> KEEPS_REPEAT = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod(GET_POSITIONS, PlacementContext.class, RandomSource.class, BlockPos.class).getDeclaringClass() == RepeatingPlacement.class;
            } catch (NoSuchMethodException | LinkageError e) {
                return false;
            }
        }
    };

    private static final FeatureTargets.Hook FILTERS = FeatureTargets.hook(FeatureTargets.PLACEMENT_FILTERS);
    private static final FeatureTargets.Hook REPEATS = FeatureTargets.hook(FeatureTargets.PLACEMENT_REPEATS);

    public static boolean filters() {
        return FILTERS.allowed();
    }

    public static boolean repeats(Class<?> type) {
        return KEEPS_REPEAT.get(type) && REPEATS.allowed();
    }
}
