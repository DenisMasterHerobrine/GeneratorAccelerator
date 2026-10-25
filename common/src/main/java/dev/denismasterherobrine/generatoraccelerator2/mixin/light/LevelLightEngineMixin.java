package dev.denismasterherobrine.generatoraccelerator2.mixin.light;

import dev.denismasterherobrine.generatoraccelerator2.light.FastLight;
import dev.denismasterherobrine.generatoraccelerator2.light.Ga2LightLayers;
import dev.denismasterherobrine.generatoraccelerator2.light.LightLayers;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Resolves whether {@link FastLight} is on once the light engine classes (and their hooks) are loaded.
 *
 * <p>Also runs the two light layers of an update at once ({@link LightLayers}). While the light thread records, each call that
 * vanilla passes to both engines runs on the sky light engine right away, and its block light part is recorded. At the
 * propagation, the lane replays the recorded calls and runs the block light propagation while this thread runs the sky light's.
 * Every other thread, and this thread outside the recording, calls both engines as vanilla does.
 */
@Mixin(LevelLightEngine.class)
public abstract class LevelLightEngineMixin implements Ga2LightLayers {
    @Shadow @Final @Nullable private LightEngine<?, ?> blockEngine;
    @Shadow @Final @Nullable private LightEngine<?, ?> skyEngine;

    /** The thread recording block light calls, or null. Only that thread reads and writes the recording. */
    @Unique @Nullable private Thread ga2$recorder;
    @Unique private List<Runnable> ga2$calls = new ArrayList<>();
    @Unique @Nullable private LightLayers.Lane ga2$lane;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void ga2$refresh(CallbackInfo ci) {
        FastLight.refresh();
    }

    @Override
    public void ga2$beginLayers() {
        if (this.blockEngine != null && this.skyEngine != null) {
            this.ga2$calls.clear();
            this.ga2$recorder = Thread.currentThread();
        }
    }

    /** Whether this call's block light part is recorded instead of run. */
    @Unique
    private boolean ga2$recording() {
        return this.ga2$recorder == Thread.currentThread();
    }

    @Inject(method = "checkBlock", at = @At("HEAD"), cancellable = true)
    private void ga2$checkBlock(BlockPos pos, CallbackInfo ci) {
        if (this.ga2$recording()) {
            LightEngine<?, ?> block = this.blockEngine;
            BlockPos at = pos.immutable();
            this.ga2$calls.add(() -> block.checkBlock(at));
            this.skyEngine.checkBlock(pos);
            ci.cancel();
        }
    }

    @Inject(method = "updateSectionStatus", at = @At("HEAD"), cancellable = true)
    private void ga2$updateSectionStatus(SectionPos pos, boolean empty, CallbackInfo ci) {
        if (this.ga2$recording()) {
            LightEngine<?, ?> block = this.blockEngine;
            this.ga2$calls.add(() -> block.updateSectionStatus(pos, empty));
            this.skyEngine.updateSectionStatus(pos, empty);
            ci.cancel();
        }
    }

    @Inject(method = "setLightEnabled", at = @At("HEAD"), cancellable = true)
    private void ga2$setLightEnabled(ChunkPos pos, boolean enabled, CallbackInfo ci) {
        if (this.ga2$recording()) {
            LightEngine<?, ?> block = this.blockEngine;
            this.ga2$calls.add(() -> block.setLightEnabled(pos, enabled));
            this.skyEngine.setLightEnabled(pos, enabled);
            ci.cancel();
        }
    }

    @Inject(method = "propagateLightSources", at = @At("HEAD"), cancellable = true)
    private void ga2$propagateLightSources(ChunkPos pos, CallbackInfo ci) {
        if (this.ga2$recording()) {
            LightEngine<?, ?> block = this.blockEngine;
            this.ga2$calls.add(() -> block.propagateLightSources(pos));
            this.skyEngine.propagateLightSources(pos);
            ci.cancel();
        }
    }

    @Inject(method = "queueSectionData", at = @At("HEAD"), cancellable = true)
    private void ga2$queueSectionData(LightLayer layer, SectionPos pos, @Nullable DataLayer data, CallbackInfo ci) {
        if (layer == LightLayer.BLOCK && this.ga2$recording()) {
            LightEngine<?, ?> block = this.blockEngine;
            long key = pos.asLong();
            this.ga2$calls.add(() -> block.queueSectionData(key, data));
            ci.cancel();
        }
    }

    @Inject(method = "retainData", at = @At("HEAD"), cancellable = true)
    private void ga2$retainData(ChunkPos pos, boolean retain, CallbackInfo ci) {
        if (this.ga2$recording()) {
            LightEngine<?, ?> block = this.blockEngine;
            this.ga2$calls.add(() -> block.retainData(pos, retain));
            this.skyEngine.retainData(pos, retain);
            ci.cancel();
        }
    }

    /**
     * The propagation of an update that recorded: the lane replays the block light calls and propagates block light, this thread
     * propagates sky light, as vanilla's two calls. Few calls and no block light work run here, in vanilla's order.
     */
    @Inject(method = "runLightUpdates", at = @At("HEAD"), cancellable = true)
    private void ga2$runLightUpdates(CallbackInfoReturnable<Integer> cir) {
        if (!this.ga2$recording()) {
            return;
        }
        this.ga2$recorder = null;
        List<Runnable> calls = this.ga2$calls;
        LightEngine<?, ?> block = this.blockEngine;
        if (calls.size() < LightLayers.MIN_CALLS && !block.hasLightWork()) {
            for (Runnable call : calls) {
                call.run();
            }
            calls.clear();
            LightLayers.inline();
            int processed = block.runLightUpdates();
            cir.setReturnValue(processed + this.skyEngine.runLightUpdates());
            return;
        }
        LightLayers.Lane lane = this.ga2$lane;
        if (lane == null) {
            lane = new LightLayers.Lane();
            this.ga2$lane = lane;
        }
        // The lane takes this list. The next update records into a new one.
        this.ga2$calls = new ArrayList<>(Math.max(16, calls.size()));
        lane.start(calls, block);
        int sky;
        try {
            sky = this.skyEngine.runLightUpdates();
        } catch (RuntimeException | Error e) {
            // The lane still finishes before this thread goes on. The sky light's failure is the one reported.
            try {
                lane.join();
            } catch (RuntimeException | Error ignored) {
            }
            throw e;
        }
        // The block light is done before the tasks after the propagation run, and before any other light call.
        cir.setReturnValue(lane.join() + sky);
    }
}
