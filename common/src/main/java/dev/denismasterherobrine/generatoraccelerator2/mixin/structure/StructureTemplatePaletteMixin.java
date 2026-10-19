package dev.denismasterherobrine.generatoraccelerator2.mixin.structure;

import dev.denismasterherobrine.generatoraccelerator2.structure.JigsawBlocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** A palette's rotated jigsaw blocks, one slot per rotation ({@link JigsawBlocks}). */
@Mixin(StructureTemplate.Palette.class)
public abstract class StructureTemplatePaletteMixin implements JigsawBlocks.PaletteCache {
    @Unique
    private final Object[] ga2$jigsaws = new Object[4];

    @Override
    public Object[] ga2$jigsaws() {
        return this.ga2$jigsaws;
    }
}
