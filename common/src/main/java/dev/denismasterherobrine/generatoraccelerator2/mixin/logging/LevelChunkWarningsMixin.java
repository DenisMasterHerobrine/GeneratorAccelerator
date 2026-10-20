package dev.denismasterherobrine.generatoraccelerator2.mixin.logging;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.logging.RepeatedWarnings;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Logs the block entity warnings of a chunk's promotion to a full chunk as {@code worldgenWarnings} says
 * ({@link RepeatedWarnings}). The source is the block. The first warning is a placeholder block entity on a block that has
 * none. The second is a block entity that could not be made.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkWarningsMixin {
    @WrapOperation(method = "promotePendingBlockEntity", at = @At(value = "INVOKE",
        target = "Lorg/slf4j/Logger;warn(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V", ordinal = 0), require = 0)
    private void ga2$dummyOnce(Logger logger, String format, Object pos, Object state, Operation<Void> original) {
        ga2$warn(RepeatedWarnings.Kind.DUMMY_BLOCK_ENTITY, ga2$block(state), logger, format, pos, state, original);
    }

    @WrapOperation(method = "promotePendingBlockEntity", at = @At(value = "INVOKE",
        target = "Lorg/slf4j/Logger;warn(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V", ordinal = 1), require = 0)
    private void ga2$failedOnce(Logger logger, String format, Object state, Object pos, Operation<Void> original) {
        ga2$warn(RepeatedWarnings.Kind.FAILED_BLOCK_ENTITY, ga2$block(state), logger, format, state, pos, original);
    }

    @Unique
    private static void ga2$warn(RepeatedWarnings.Kind kind, String source, Logger logger, String format, Object a, Object b, Operation<Void> original) {
        switch (RepeatedWarnings.decide(kind, source)) {
            case LOG -> original.call(logger, format, a, b);
            case DEBUG -> RepeatedWarnings.debug(format, a, b);
            case COUNT -> {}
        }
    }

    @Unique
    private static String ga2$block(Object state) {
        return state instanceof BlockState s ? BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString() : String.valueOf(state);
    }
}
