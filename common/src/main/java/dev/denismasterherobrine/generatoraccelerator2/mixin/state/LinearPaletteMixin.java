package dev.denismasterherobrine.generatoraccelerator2.mixin.state;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.LinearPalette;
import net.minecraft.world.level.chunk.PaletteResize;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code LinearPalette.idFor} returns the id of the last requested state without the scan. Writes come in runs of one state,
 * such as an ore's target or a tree's logs. A palette's entries never move, so the remembered id stays valid until the palette
 * is read anew. Put in place once the class is complete, when no other mod changed {@code idFor} ({@code compat.LateOverwrites}).
 * Another mod's change to these methods keeps vanilla's scan for every call ({@code state.palette}).
 */
@Mixin(value = LinearPalette.class, priority = 5000)
public abstract class LinearPaletteMixin<T> {
    @Shadow
    @Final
    private T[] values;
    @Shadow
    @Final
    private PaletteResize<T> resizeHandler;
    @Shadow
    @Final
    private int bits;
    @Shadow
    private int size;

    @Unique
    private Object ga2$last;
    @Unique
    private int ga2$lastId;
    @Unique
    private static final HookTargets.Hook GA2$HOOK = HookTargets.hook(HookTargets.PALETTE);

    /** The body of {@code idFor(state)}. Returns the remembered last answer, or else runs vanilla's scan. */
    @Unique
    private int ga2$idFor(T state) {
        if (state == this.ga2$last && (dev.denismasterherobrine.generatoraccelerator2.state.StateFast.ENABLED && GA2$HOOK.allowed())) {
            return this.ga2$lastId;
        }
        for (int i = 0; i < this.size; i++) {
            if (this.values[i] == state) {
                this.ga2$last = state;
                this.ga2$lastId = i;
                return i;
            }
        }
        int j = this.size;
        if (j < this.values.length) {
            this.values[j] = state;
            this.size++;
            this.ga2$last = state;
            this.ga2$lastId = j;
            return j;
        }
        return this.resizeHandler.onResize(this.bits + 1, state);
    }

    @Inject(method = "read", at = @At("HEAD"))
    private void ga2$forget(FriendlyByteBuf buf, CallbackInfo ci) {
        this.ga2$last = null;
    }
}
