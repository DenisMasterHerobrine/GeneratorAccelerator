package dev.denismasterherobrine.generatoraccelerator2.mixin.aquifer;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Aquifer.FluidStatus.class)
public interface FluidStatusAccessor {
    @Accessor("fluidLevel") int ga2$fluidLevel();
    @Accessor("fluidType") BlockState ga2$fluidType();
}
