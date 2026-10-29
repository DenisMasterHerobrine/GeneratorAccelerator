package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The status of an aquifer cell and the barrier noise, as the chunk's aquifer has them (verification). */
@Mixin(Aquifer.NoiseBasedAquifer.class)
public interface AquiferStatusInvoker {
    @Invoker("getIndex")
    int ga2$getIndex(int gridX, int gridY, int gridZ);

    @Invoker("getAquiferStatus")
    Aquifer.FluidStatus ga2$getAquiferStatus(int index);

    @Accessor("aquiferLocationCache")
    long[] ga2$locations();

    @Accessor("barrierNoise")
    DensityFunction ga2$barrierNoise();
}
