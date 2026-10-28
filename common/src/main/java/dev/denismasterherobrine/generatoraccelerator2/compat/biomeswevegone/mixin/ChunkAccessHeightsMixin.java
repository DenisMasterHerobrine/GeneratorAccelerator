package dev.denismasterherobrine.generatoraccelerator2.compat.biomeswevegone.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.denismasterherobrine.generatoraccelerator2.compat.biomeswevegone.BiomesWeveGoneTerrain;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Oh The Biomes We've Gone gives every chunk a cache of generator heights ({@code GeneratorHeightGetter.getHeight}). The
 * mod's {@code ChunkAccessMixin} merges this method into the chunk. The cache is an array created on first use, then
 * filled column by column without synchronization. The mod's basalt barrera terrain asks chunks up to eight chunks away.
 * GA2 runs that terrain on several threads at once ({@code BiomesWeveGoneTerrain}). If two threads create the array at
 * once, one thread could get the other thread's still empty array. GA2 uses each chunk's cache under a lock. The heights
 * come from the generator and are the same whichever thread asks first. This mixin has a higher priority than the mod's
 * mixin, so it can wrap the merged method. Only applied with the mod loaded.
 */
@Mixin(value = ChunkAccess.class, priority = 2000)
public abstract class ChunkAccessHeightsMixin {
    @Dynamic("GeneratorHeightGetter.getHeight, merged by Oh The Biomes We've Gone's ChunkAccessMixin")
    // The mod's method is not in the game's mappings, so its descriptor is written in both namespaces. These are Mojang's
    // (Forge, development) and intermediary (Fabric). A bare name would also match vanilla's getHeight() in development.
    @WrapMethod(method = {
        "getHeight(Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/world/level/levelgen/Heightmap$Types;IILnet/minecraft/world/level/levelgen/RandomState;Z)I",
        "getHeight(Lnet/minecraft/class_2794;Lnet/minecraft/class_2902$class_2903;IILnet/minecraft/class_7138;Z)I" },
        require = 0)
    private int ga2$lockedGeneratorHeight(ChunkGenerator generator, Heightmap.Types type, int x, int z, RandomState randomState, boolean generatorOnly,
                                          Operation<Integer> original) {
        synchronized (BiomesWeveGoneTerrain.heightLock(this)) {
            return original.call(generator, type, x, z, randomState, generatorOnly);
        }
    }
}
