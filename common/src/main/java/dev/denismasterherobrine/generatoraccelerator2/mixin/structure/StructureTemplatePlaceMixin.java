package dev.denismasterherobrine.generatoraccelerator2.mixin.structure;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.structure.TemplatePlacement;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@code placeInWorld}'s call of {@code processBlockInfos}, clipped to the bounding box ({@link TemplatePlacement}). Vanilla's
 * five-argument call, as Fabric runs it. NeoForge's call has a sixth argument ({@link StructureTemplatePlaceNeoMixin}); the
 * mixin plugin applies the one for the running loader.
 */
@Mixin(value = StructureTemplate.class, priority = 5000)
public abstract class StructureTemplatePlaceMixin {
    @WrapOperation(method = "placeInWorld(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;"
        + "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructurePlaceSettings;Lnet/minecraft/util/RandomSource;I)Z",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate;processBlockInfos("
            + "Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructurePlaceSettings;Ljava/util/List;)Ljava/util/List;"))
    private List<StructureTemplate.StructureBlockInfo> ga2$clipped(ServerLevelAccessor level, BlockPos offset, BlockPos pivot, StructurePlaceSettings settings,
                                                                   List<StructureTemplate.StructureBlockInfo> blocks,
                                                                   Operation<List<StructureTemplate.StructureBlockInfo>> original) {
        List<StructureTemplate.StructureBlockInfo> clipped = TemplatePlacement.clipped(level, offset, pivot, settings, blocks, null);
        return clipped != null ? clipped : original.call(level, offset, pivot, settings, blocks);
    }
}
