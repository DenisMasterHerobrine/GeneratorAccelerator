package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import java.util.List;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@code applyBiomeDecoration} asks the structure manager for the starts of every registered structure in the chunk it decorates.
 * The manager looks the chunk up through the region and builds a list each time, nearly always an empty one: a chunk refers to a
 * handful of structures. A pack with 1,500 structures paid that 1,500 times per chunk. A structure the chunk has no reference to
 * gets the empty list at once, which is what the manager returns for it. The seeds and the order stay vanilla's. Hook
 * {@code structureStarts}: another mod's change to the call keeps the manager's.
 */
@Mixin(ChunkGenerator.class)
public abstract class StructureStartsLookupMixin {
    @Unique
    private static final FeatureTargets.Hook GA2_HOOK = FeatureTargets.hook("structureStarts");

    @WrapOperation(method = "applyBiomeDecoration", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/StructureManager;startsForStructure(Lnet/minecraft/core/SectionPos;Lnet/minecraft/world/level/levelgen/structure/Structure;)Ljava/util/List;"),
        require = 0)
    private List<StructureStart> ga2$onlyReferenced(StructureManager manager, SectionPos pos, Structure structure, Operation<List<StructureStart>> original,
                                                    @Local(argsOnly = true) ChunkAccess chunk) {
        if (GA2_HOOK.allowed() && chunk.getPos().x == pos.x() && chunk.getPos().z == pos.z() && chunk.getReferencesForStructure(structure).isEmpty()) {
            return ImmutableList.of();
        }
        return original.call(manager, pos, structure);
    }
}
