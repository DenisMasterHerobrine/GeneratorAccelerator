package dev.denismasterherobrine.generatoraccelerator2.mixin.state;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.state.PaletteCopy;
import java.util.Arrays;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PaletteResize;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A container's resize with one palette lookup per distinct old id instead of two per entry. On resize, {@code onResize}
 * copies the old entries into a palette one bit wider. Vanilla asks the old palette for each entry's value and the new palette
 * for that value's id, entry by entry. The new palette hands out ids in the order values are first asked for. Asking again for
 * a value the new palette already holds changes nothing. Here the first entry of each old id asks exactly as vanilla does, so
 * the new palette gets the same values in the same order. Later entries of that id reuse the answer. The resulting palette and
 * storage are the same. Applies only to old palettes of up to 8 bits, which is every palette except the global one. A wider
 * palette is copied as vanilla does.
 */
@Mixin(PalettedContainer.Data.class)
public abstract class PaletteCopyMixin<T> {
    @Shadow @Final private BitStorage storage;
    @Shadow @Final private Palette<T> palette;

    @Unique
    private static final HookTargets.Hook GA2$HOOK = HookTargets.hook(HookTargets.PALETTE_COPY);
    @Unique
    private static final ThreadLocal<int[][]> GA2$SCRATCH = ThreadLocal.withInitial(() -> new int[][] { new int[4096], new int[256] });

    @Inject(method = "copyFrom", at = @At("HEAD"), cancellable = true)
    private void ga2$copyFrom(Palette<T> from, BitStorage fromStorage, CallbackInfo ci) {
        int bits = fromStorage.getBits();
        int size = fromStorage.getSize();
        if (!PaletteCopy.ENABLED || bits > 8 || size > 4096 || !GA2$HOOK.allowed()) {
            return;
        }
        int[][] scratch = GA2$SCRATCH.get();
        int[] ids = scratch[0];
        int[] remap = scratch[1];
        fromStorage.unpack(ids);
        Arrays.fill(remap, 0, 1 << bits, -1);
        BitStorage to = this.storage;
        Palette<T> toPalette = this.palette;
        int lastOld = -1;
        int lastNew = -1;
        for (int i = 0; i < size; i++) {
            int old = ids[i];
            if (old != lastOld) {
                int id = remap[old];
                if (id < 0) {
                    // First entry with this id. Make vanilla's two calls, in vanilla's order.
                    id = toPalette.idFor(from.valueFor(old), PaletteResize.noResizeExpected());
                    remap[old] = id;
                }
                lastOld = old;
                lastNew = id;
            }
            to.set(i, lastNew);
        }
        ci.cancel();
    }
}
