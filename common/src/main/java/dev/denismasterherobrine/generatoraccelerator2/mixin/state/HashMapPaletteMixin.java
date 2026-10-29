package dev.denismasterherobrine.generatoraccelerator2.mixin.state;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.state.StateFast;
import net.minecraft.core.IdMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.CrudeIncrementalIntIdentityHashBiMap;
import net.minecraft.world.level.chunk.HashMapPalette;
import net.minecraft.world.level.chunk.PaletteResize;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@code HashMapPalette.idFor} returns the id of the last requested state without hashing. See {@link LinearPaletteMixin}. */
@Mixin(value = HashMapPalette.class, priority = 5000)
public abstract class HashMapPaletteMixin<T> {
    @Shadow
    @Final
    private CrudeIncrementalIntIdentityHashBiMap<T> values;
    @Shadow
    @Final
    private int bits;

    @Unique
    private Object ga2$last;
    @Unique
    private int ga2$lastId;
    @Unique
    private static final HookTargets.Hook GA2$HOOK = HookTargets.hook(HookTargets.PALETTE);

    /** The body of {@code idFor(state, resizeHandler)}. Returns the remembered last answer, or else runs vanilla's lookup. */
    @Unique
    private int ga2$idFor(T state, PaletteResize<T> resizeHandler) {
        if (state == this.ga2$last && (StateFast.ENABLED && GA2$HOOK.allowed())) {
            return this.ga2$lastId;
        }
        int i = this.values.getId(state);
        if (i == -1) {
            i = this.values.add(state);
            if (i >= 1 << this.bits) {
                return resizeHandler.onResize(this.bits + 1, state);
            }
        }
        this.ga2$last = state;
        this.ga2$lastId = i;
        return i;
    }

    @Inject(method = "read", at = @At("HEAD"))
    private void ga2$forget(FriendlyByteBuf buf, IdMap<T> globalMap, CallbackInfo ci) {
        this.ga2$last = null;
    }
}
