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

/** {@link TileTickerMixin} for Lootr 0.7 on Fabric. Applied only with {@link LootrMixins#tickerKnown}. */
@Mixin(targets = "net.zestyblaze.lootr.block.entities.TileTicker", remap = false)
public abstract class FabricTileTickerMixin {
    @ModifyExpressionValue(method = {"addEntry", "serverTick"}, at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
        target = "Lnet/zestyblaze/lootr/block/entities/TileTicker;tileEntries:Ljava/util/Set;"))
    private static Set<Object> ga2$entries(Set<Object> entries) {
        return LootrEntries.ENTRIES;
    }

    /** The tick's first read of the set is the copy. */
    @Inject(method = "serverTick", at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
        target = "Lnet/zestyblaze/lootr/block/entities/TileTicker;tileEntries:Ljava/util/Set;", ordinal = 0))
    private static void ga2$hideUnloaded(CallbackInfo ci) {
        LootrEntries.ENTRIES.hideUnloaded();
    }
}
