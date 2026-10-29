package dev.denismasterherobrine.generatoraccelerator2.mixin.join;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.denismasterherobrine.generatoraccelerator2.join.JoinWait;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A joining player enters once the chunks within {@code spawnAreaRadius} of them are complete ({@link JoinWait}). The check
 * stands in for the second {@code isDone} of {@code tick}, which asks whether vanilla's 7x7 load is done.
 */
@Mixin(targets = "net.minecraft.server.network.config.PrepareSpawnTask$Preparing")
public abstract class PrepareSpawnJoinMixin {
    @Shadow @Final private ServerLevel spawnLevel;

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;isDone()Z", ordinal = 1))
    private boolean ga2$areaReady(CompletableFuture<?> load, Operation<Boolean> original, @Local Vec3 spawnPosition) {
        return JoinWait.playerAreaReady(this.spawnLevel, spawnPosition, original.call(load));
    }
}
