package dev.denismasterherobrine.generatoraccelerator2.mixin.structure;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.structure.JigsawLists;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A piece's jigsaw blocks at position zero from the lists kept per template and rotation ({@link JigsawLists}). */
@Mixin(SinglePoolElement.class)
public abstract class SinglePoolElementJigsawsMixin {
    @WrapOperation(method = "getShuffledJigsawBlocks", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate;getJigsaws(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Rotation;)Ljava/util/List;"))
    private List<StructureTemplate.JigsawBlockInfo> ga2$keptJigsaws(StructureTemplate template, BlockPos pos, Rotation rotation,
                                                                    Operation<List<StructureTemplate.JigsawBlockInfo>> original) {
        return JigsawLists.get(template, pos, rotation, original);
    }
}
