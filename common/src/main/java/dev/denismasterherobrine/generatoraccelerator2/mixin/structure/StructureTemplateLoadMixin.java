package dev.denismasterherobrine.generatoraccelerator2.mixin.structure;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.structure.TemplatePositions;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The positions of a loaded template's blocks, one object per position across all templates ({@link TemplatePositions}). */
@Mixin(StructureTemplate.class)
public abstract class StructureTemplateLoadMixin {
    // the block list is read in a lambda of loadPalette
    @WrapOperation(method = "lambda$loadPalette$0", at = @At(value = "NEW", target = "(III)Lnet/minecraft/core/BlockPos;"))
    private static BlockPos ga2$sharedPosition(int x, int y, int z, Operation<BlockPos> original) {
        return TemplatePositions.ENABLED ? TemplatePositions.of(x, y, z) : original.call(x, y, z);
    }
}
