package dev.denismasterherobrine.generatoraccelerator2.mixin.structure;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.denismasterherobrine.generatoraccelerator2.structure.StructureLocks;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressStructure;
import org.spongepowered.asm.mixin.Mixin;

/** Lays out one nether fortress at a time: its piece weights and counts are shared static objects. */
@Mixin(NetherFortressStructure.class)
public abstract class NetherFortressStructureMixin {
    @WrapMethod(method = "generatePieces")
    private static void ga2$oneAtATime(StructurePiecesBuilder builder, Structure.GenerationContext context, Operation<Void> original) {
        synchronized (StructureLocks.NETHER_FORTRESS) {
            original.call(builder, context);
        }
    }
}
