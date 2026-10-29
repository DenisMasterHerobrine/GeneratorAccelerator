package dev.denismasterherobrine.generatoraccelerator2.mixin.structure;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.entity.variant.SpawnContext;
import net.minecraft.world.entity.variant.StructureCheck;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A mob a structure places during decoration picks its variant, and a variant may ask for a structure: a cat in a swamp hut is
 * black. The check goes through the server level. Off the main thread, the server level waits for the main thread to hand over
 * the chunk. When the mob stands in the chunk being decorated, the generation region already holds that chunk and every
 * structure start the chunk references, all within the region radius. The region's structure view therefore gives the same
 * answer without the wait.
 */
@Mixin(StructureCheck.class)
public abstract class VariantStructureCheckMixin {
    @WrapOperation(method = "test(Lnet/minecraft/world/entity/variant/SpawnContext;)Z", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ServerLevel;structureManager()Lnet/minecraft/world/level/StructureManager;"))
    private StructureManager ga2$regionStructures(ServerLevel level, Operation<StructureManager> original, @Local(argsOnly = true) SpawnContext context) {
        if (context.level() instanceof WorldGenRegion region && ChunkPos.containing(context.pos()).equals(region.getCenter())) {
            return original.call(level).forWorldGenRegion(region);
        }
        return original.call(level);
    }
}
