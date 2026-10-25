package dev.denismasterherobrine.generatoraccelerator2.mixin.light;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.light.Ga2LayerMap;
import dev.denismasterherobrine.generatoraccelerator2.light.Ga2SnapshotCopy;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.chunk.DataLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The block light map's {@code copy} takes the published snapshot instead of cloning the section map, when one is set
 * ({@link dev.denismasterherobrine.generatoraccelerator2.light.LightSnapshot}).
 */
@Mixin(targets = "net.minecraft.world.level.lighting.BlockLightSectionStorage$BlockDataLayerStorageMap")
public abstract class BlockLayerMapCopyMixin implements Ga2SnapshotCopy {
    @WrapOperation(method = "copy()Lnet/minecraft/world/level/lighting/BlockLightSectionStorage$BlockDataLayerStorageMap;", at = @At(value = "INVOKE",
        target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap;clone()Lit/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap;"))
    private Long2ObjectOpenHashMap<DataLayer> ga2$snapshot(Long2ObjectOpenHashMap<DataLayer> map, Operation<Long2ObjectOpenHashMap<DataLayer>> original) {
        Long2ObjectOpenHashMap<DataLayer> with = ((Ga2LayerMap) (Object) this).ga2$copyWith();
        return with != null ? with : original.call(map);
    }
}
