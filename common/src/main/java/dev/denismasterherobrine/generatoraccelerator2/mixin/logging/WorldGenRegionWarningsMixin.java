package dev.denismasterherobrine.generatoraccelerator2.mixin.logging;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.denismasterherobrine.generatoraccelerator2.logging.RepeatedWarnings;
import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStep;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Logs a generation region's far-chunk writes and missing block entities as {@code worldgenWarnings} says ({@link RepeatedWarnings}).
 * The source is the placed feature or structure being generated. Without one, it is the step's status or the block.
 */
@Mixin(WorldGenRegion.class)
public abstract class WorldGenRegionWarningsMixin {
    @Shadow private Supplier<String> currentlyGenerating;
    @Shadow @Final private ChunkStep generatingStep;

    @WrapOperation(method = "ensureCanWrite", at = @At(value = "INVOKE", target = "Lnet/minecraft/Util;logAndPauseIfInIde(Ljava/lang/String;)V"),
        require = 0)
    private void ga2$farWriteOnce(String message, Operation<Void> original) {
        String generating = RepeatedWarnings.generating(this.currentlyGenerating);
        String source = generating != null ? generating : "status " + this.generatingStep.targetStatus().getName();
        switch (RepeatedWarnings.decide(RepeatedWarnings.Kind.FAR_WRITE, source)) {
            case LOG -> original.call(message);
            case DEBUG -> RepeatedWarnings.debug("{}", message);
            case COUNT -> {}
        }
    }

    @WrapOperation(method = "getBlockEntity", at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;warn(Ljava/lang/String;Ljava/lang/Object;)V"),
        require = 0)
    private void ga2$missingBlockEntityOnce(Logger logger, String format, Object pos, Operation<Void> original, @Local BlockState state) {
        String generating = RepeatedWarnings.generating(this.currentlyGenerating);
        String block = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        switch (RepeatedWarnings.decide(RepeatedWarnings.Kind.MISSING_BLOCK_ENTITY, generating != null ? block + " in " + generating : block)) {
            case LOG -> original.call(logger, format, pos);
            case DEBUG -> RepeatedWarnings.debug(format, pos);
            case COUNT -> {}
        }
    }
}
