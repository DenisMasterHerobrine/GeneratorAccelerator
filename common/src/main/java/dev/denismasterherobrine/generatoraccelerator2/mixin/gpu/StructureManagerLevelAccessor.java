package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.StructureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The level a structure manager reads (a step's generation region). The GPU's surface reads the biomes around a chunk from it. */
@Mixin(StructureManager.class)
public interface StructureManagerLevelAccessor {
    @Accessor("level")
    LevelAccessor ga2$level();
}
