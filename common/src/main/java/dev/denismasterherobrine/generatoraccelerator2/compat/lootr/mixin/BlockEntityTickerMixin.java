package dev.denismasterherobrine.generatoraccelerator2.compat.lootr.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.denismasterherobrine.generatoraccelerator2.compat.lootr.LootrEntries;
import dev.denismasterherobrine.generatoraccelerator2.compat.lootr.LootrMixins;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The converter of Lootr 1.10.34 and older keeps its entries in GA2's {@link LootrEntries}. Every read of its entry set gets GA2's
 * set, and the field itself stays empty. Lootr's copy and walk each tick then cover only chunks at FULL. Applied only with
 * {@link LootrMixins#tickerKnown}.
 */
@Mixin(targets = "noobanidus.mods.lootr.common.block.entity.BlockEntityTicker", remap = false)
public abstract class BlockEntityTickerMixin {
    @ModifyExpressionValue(method = {"addEntry", "onServerTick"}, at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
        target = "Lnoobanidus/mods/lootr/common/block/entity/BlockEntityTicker;blockEntityEntries:Ljava/util/Set;"))
    private static Set<Object> ga2$entries(Set<Object> entries) {
        return LootrEntries.ENTRIES;
    }

    /** The tick's first read of the set is the copy. */
    @Inject(method = "onServerTick", at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
        target = "Lnoobanidus/mods/lootr/common/block/entity/BlockEntityTicker;blockEntityEntries:Ljava/util/Set;", ordinal = 0))
    private static void ga2$hideUnloaded(CallbackInfo ci) {
        LootrEntries.ENTRIES.hideUnloaded();
    }
}
