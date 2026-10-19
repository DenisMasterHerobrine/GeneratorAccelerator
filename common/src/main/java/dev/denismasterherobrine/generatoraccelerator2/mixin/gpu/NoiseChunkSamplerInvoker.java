package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import java.util.List;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseRouter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The chunk's climate sampler, obtained as vanilla's biome step does. The GPU path samples it for quarts left to the CPU (ties). */
@Mixin(NoiseChunk.class)
public interface NoiseChunkSamplerInvoker {
    @Invoker("cachedClimateSampler")
    Climate.Sampler ga2$cachedClimateSampler(NoiseRouter router, List<Climate.ParameterPoint> spawnTarget);
}
