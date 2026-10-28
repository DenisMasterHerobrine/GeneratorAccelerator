package dev.denismasterherobrine.generatoraccelerator2.mixin.structure;

import dev.denismasterherobrine.generatoraccelerator2.structure.JigsawBlocks;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A palette's rotated jigsaw blocks, one slot per rotation ({@link JigsawBlocks}), and a palette's blocks by block made safe for
 * several threads. {@code blocks(Block)} memoizes into a plain hash map. Decorations run at once, and two features placing the
 * same template (Oh The Trees You'll Grow's trees from structure files) broke that map with a concurrent modification. The map is
 * a concurrent one instead: the same lists, made once per block.
 */
@Mixin(StructureTemplate.Palette.class)
public abstract class StructureTemplatePaletteMixin implements JigsawBlocks.PaletteCache {
    @Shadow
    @Final
    @Mutable
    private Map<Block, List<StructureTemplate.StructureBlockInfo>> cache;

    @Unique
    private final Object[] ga2$jigsaws = new Object[4];

    @Inject(method = "<init>", at = @At("RETURN"))
    private void ga2$concurrentCache(List<StructureTemplate.StructureBlockInfo> blocks, CallbackInfo ci) {
        this.cache = new ConcurrentHashMap<>();
    }

    @Override
    public Object[] ga2$jigsaws() {
        return this.ga2$jigsaws;
    }
}
