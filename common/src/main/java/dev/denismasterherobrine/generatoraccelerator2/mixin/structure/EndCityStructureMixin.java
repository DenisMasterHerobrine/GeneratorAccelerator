package dev.denismasterherobrine.generatoraccelerator2.mixin.structure;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.denismasterherobrine.generatoraccelerator2.structure.StructureLocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.structures.EndCityStructure;
import org.spongepowered.asm.mixin.Mixin;

/** Lays out one end city at a time: {@code EndCityPieces} tracks the placed ship in a static generator. */
@Mixin(EndCityStructure.class)
public abstract class EndCityStructureMixin {
    @WrapMethod(method = "generatePieces")
    private void ga2$oneAtATime(StructurePiecesBuilder builder, BlockPos pos, Rotation rotation, Structure.GenerationContext context, Operation<Void> original) {
        synchronized (StructureLocks.END_CITY) {
            original.call(builder, pos, rotation, context);
        }
    }
}
