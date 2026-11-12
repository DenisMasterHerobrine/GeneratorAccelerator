package dev.denismasterherobrine.generatoraccelerator2.mixin.storage;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.memory.ContainerLocks;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import net.minecraft.core.IdMap;
import net.minecraft.util.ThreadingDetector;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A block or biome container without its own lock objects ({@link ContainerLocks}). The detector is not made. {@code acquire} sets
 * the thread inside, {@code release} clears it, and a thread that finds another inside crashes with vanilla's report. Priority 1500,
 * so a mod that overwrites the two methods with the default priority leaves the injections valid. While such a mod is there, the
 * handlers do nothing.
 */
@Mixin(value = PalettedContainer.class, priority = 1500)
public abstract class PalettedContainerLockMixin {
    @Unique
    private static final boolean GA2_LIGHT_LOCK = ContainerLocks.active();
    @Unique
    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<PalettedContainer, Thread> GA2_INSIDE =
        AtomicReferenceFieldUpdater.newUpdater(PalettedContainer.class, Thread.class, "ga2$inside");

    @Unique
    private volatile Thread ga2$inside;

    /** All three constructors. A bare {@code <init>} selects only the first. */
    @WrapOperation(method = "<init>*", at = @At(value = "NEW", target = "(Ljava/lang/String;)Lnet/minecraft/util/ThreadingDetector;"), require = 0)
    private ThreadingDetector ga2$noDetector(String name, Operation<ThreadingDetector> original) {
        return GA2_LIGHT_LOCK ? null : original.call(name);
    }

    @Inject(method = "acquire", at = @At("HEAD"), cancellable = true)
    private void ga2$acquire(CallbackInfo ci) {
        if (GA2_LIGHT_LOCK) {
            if (!GA2_INSIDE.compareAndSet((PalettedContainer) (Object) this, null, Thread.currentThread())) {
                throw ThreadingDetector.makeThreadingException("PalettedContainer", this.ga2$inside);
            }
            ci.cancel();
        }
    }

    @Inject(method = "release", at = @At("HEAD"), cancellable = true)
    private void ga2$release(CallbackInfo ci) {
        if (GA2_LIGHT_LOCK) {
            this.ga2$inside = null;
            ci.cancel();
        }
    }

    @WrapOperation(method = "createOrReuseData", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/chunk/PalettedContainer$Strategy;getConfiguration(Lnet/minecraft/core/IdMap;I)Lnet/minecraft/world/level/chunk/PalettedContainer$Configuration;"),
        require = 0)
    private PalettedContainer.Configuration<?> ga2$sharedConfiguration(PalettedContainer.Strategy strategy, IdMap<?> registry, int bits,
                                                                     Operation<PalettedContainer.Configuration<?>> original) {
        PalettedContainer.Configuration<?> configuration = original.call(strategy, registry, bits);
        return GA2_LIGHT_LOCK ? ContainerLocks.shared(configuration) : configuration;
    }

    /** The containers read from saved chunks. */
    @WrapOperation(method = "unpack", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/chunk/PalettedContainer$Strategy;getConfiguration(Lnet/minecraft/core/IdMap;I)Lnet/minecraft/world/level/chunk/PalettedContainer$Configuration;"),
        require = 0)
    private static PalettedContainer.Configuration<?> ga2$sharedLoadedConfiguration(PalettedContainer.Strategy strategy, IdMap<?> registry, int bits,
                                                                                  Operation<PalettedContainer.Configuration<?>> original) {
        PalettedContainer.Configuration<?> configuration = original.call(strategy, registry, bits);
        return GA2_LIGHT_LOCK ? ContainerLocks.shared(configuration) : configuration;
    }
}
