package dev.denismasterherobrine.generatoraccelerator2.mixin.structure;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(StructurePlaceSettings.class)
public interface StructurePlaceSettingsAccessor {
    @Accessor("random")
    RandomSource ga2$random();
}
