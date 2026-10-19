package dev.denismasterherobrine.generatoraccelerator2.mixin.structure;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.StructureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A cat placed by a structure during decoration (swamp huts, village houses) checks whether it stands in a
 * "black cat" structure. The check goes through the server level. Off the main thread, the server level
 * waits for the main thread to hand over the chunk. When the cat stands in the chunk being decorated, the
 * generation region already holds that chunk and every structure start the chunk references, all within
 * the region radius. The region's structure view therefore gives the same answer without the wait.
 */
@Mixin(Cat.class)
public abstract class CatMixin {
    @WrapOperation(method = "finalizeSpawn", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;structureManager()Lnet/minecraft/world/level/StructureManager;"))
    private StructureManager ga2$regionStructures(ServerLevel level, Operation<StructureManager> original, @Local(argsOnly = true) ServerLevelAccessor accessor) {
        if (accessor instanceof WorldGenRegion region && new ChunkPos(((Entity) (Object) this).blockPosition()).equals(region.getCenter())) {
            return original.call(level).forWorldGenRegion(region);
        }
        return original.call(level);
    }
}
