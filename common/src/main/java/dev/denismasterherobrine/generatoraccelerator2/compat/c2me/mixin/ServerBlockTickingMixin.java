package dev.denismasterherobrine.generatoraccelerator2.compat.c2me.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.compat.c2me.PostProcessingLocks;
import it.unimi.dsi.fastutil.shorts.ShortList;
import it.unimi.dsi.fastutil.shorts.ShortListIterator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * C2ME's fluid filter walks a chunk's post-processing lists under their monitors ({@link PostProcessingLocks}). A save of the
 * chunk on the server thread then never sees a list in the middle of a removal. Only applied with C2ME loaded. A C2ME version
 * without this walk is left as it is.
 */
@Mixin(targets = "com.ishland.c2me.rewrites.chunksystem.common.statuses.ServerBlockTicking")
public abstract class ServerBlockTickingMixin {
    @WrapOperation(method = "filterFluidTicks",
        at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/shorts/ShortList;iterator()Lit/unimi/dsi/fastutil/shorts/ShortListIterator;"),
        require = 0)
    private static ShortListIterator ga2$lockedWalk(ShortList list, Operation<ShortListIterator> original) {
        return PostProcessingLocks.iterator(list, original.call(list));
    }
}
