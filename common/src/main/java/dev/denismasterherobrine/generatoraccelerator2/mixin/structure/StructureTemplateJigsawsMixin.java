package dev.denismasterherobrine.generatoraccelerator2.mixin.structure;

import dev.denismasterherobrine.generatoraccelerator2.structure.JigsawLists;
import java.util.List;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Holds a template's kept jigsaw lists ({@link JigsawLists}). Both methods below make the palettes again: a template read from its
 * file, or saved by a structure block. The lists go before and after, so none made from the old palettes stays.
 */
@Mixin(StructureTemplate.class)
public abstract class StructureTemplateJigsawsMixin implements JigsawLists.Template {
    @Unique
    private volatile List<?>[] ga2$jigsawLists;

    @Override
    public List<?>[] ga2$jigsawLists() {
        return this.ga2$jigsawLists;
    }

    @Override
    public void ga2$jigsawLists(List<?>[] lists) {
        this.ga2$jigsawLists = lists;
    }

    @Inject(method = { "load(Lnet/minecraft/core/HolderGetter;Lnet/minecraft/nbt/CompoundTag;)V",
        "fillFromWorld(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Vec3i;ZLjava/util/List;)V" },
        at = { @At("HEAD"), @At("RETURN") })
    private void ga2$palettesMade(CallbackInfo ci) {
        JigsawLists.forget(this);
    }
}
