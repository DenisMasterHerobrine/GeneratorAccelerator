package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import java.util.function.Supplier;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The generator's global fluid picker, for building a level's GPU program when the level loads ({@code Gpu.prewarm}). */
@Mixin(NoiseBasedChunkGenerator.class)
public interface GeneratorPickerAccessor {
    @Accessor("globalFluidPicker")
    Supplier<Aquifer.FluidPicker> ga2$globalFluidPicker();
}
