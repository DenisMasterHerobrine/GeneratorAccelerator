package dev.denismasterherobrine.generatoraccelerator2.compat.zeta.mixin;

import dev.denismasterherobrine.generatoraccelerator2.compat.zeta.ZetaClusters;
import dev.denismasterherobrine.generatoraccelerator2.compat.zeta.ZetaMixins;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Walks a cluster part only where the cluster can reach ({@link ZetaClusters}). Applied late, because the plugin first inspects
 * other mods' changes to the walk (hook {@code zeta.clusters}). Applied only with Quark loaded and with the code that the walk was
 * written against.
 */
@Mixin(targets = ZetaMixins.CLUSTER_BASED, priority = 5000)
public abstract class ClusterBasedGeneratorMixin {
    @Inject(method = "generateChunkPart", at = @At("HEAD"), cancellable = true, require = 0)
    private void ga2$walkInReach(BlockPos src, ChunkGenerator generator, Random random, BlockPos chunkCorner, WorldGenRegion world, CallbackInfo ci) {
        if (ZetaClusters.generateChunkPart(this, src, generator, random, chunkCorner, world)) {
            ci.cancel();
        }
    }
}
