package dev.denismasterherobrine.generatoraccelerator2.mixin.structure;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;

/**
 * One chunk at a time places a given structure start. The start is shared by every chunk it overlaps.
 * Placing the start mutates its pieces (height fixes, chest and spawner flags, template bounding boxes).
 */
@Mixin(StructureStart.class)
public abstract class StructureStartMixin {
    @WrapMethod(method = "placeInChunk")
    private void ga2$onePlacementAtATime(WorldGenLevel level, StructureManager structures, ChunkGenerator generator, RandomSource random, BoundingBox box,
                                         ChunkPos chunk, Operation<Void> original) {
        synchronized (this) {
            original.call(level, structures, generator, random, box, chunk);
        }
    }
}
