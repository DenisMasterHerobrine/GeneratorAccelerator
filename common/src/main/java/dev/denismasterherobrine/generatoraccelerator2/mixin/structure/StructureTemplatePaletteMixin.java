package dev.denismasterherobrine.generatoraccelerator2.mixin.structure;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A template palette's blocks per block, as {@code blocks(Block)} fills them. Vanilla keeps them in a plain HashMap. Two structure starts
 * that read the same template at once both fill it. GA2 runs structure starts in parallel, and vanilla's workers do too.
 * {@code HashMap.computeIfAbsent} throws when the map changes during the call (seen with Twilight Forest's Lich Tower). A concurrent
 * map gives the same lists.
 */
@Mixin(StructureTemplate.Palette.class)
public abstract class StructureTemplatePaletteMixin {
    @Shadow
    @Final
    @Mutable
    private Map<Block, List<StructureTemplate.StructureBlockInfo>> cache;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void ga2$concurrentCache(List<StructureTemplate.StructureBlockInfo> blocks, CallbackInfo ci) {
        this.cache = new ConcurrentHashMap<>();
    }
}
