package dev.denismasterherobrine.generatoraccelerator2.mixin.surface;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BlockColumn;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(SurfaceSystem.class)
public interface SurfaceSystemAccessor {
    @Accessor("defaultBlock")
    BlockState ga2$defaultBlock();

    @Accessor("surfaceNoise")
    NormalNoise ga2$surfaceNoise();

    @Accessor("surfaceSecondaryNoise")
    NormalNoise ga2$surfaceSecondaryNoise();

    @Accessor("noiseRandom")
    PositionalRandomFactory ga2$noiseRandom();

    @Accessor("seaLevel")
    int ga2$seaLevel();

    @Accessor("clayBands")
    BlockState[] ga2$clayBands();

    @Accessor("clayBandsOffsetNoise")
    NormalNoise ga2$clayBandsOffsetNoise();

    @Invoker("getSurfaceSecondary")
    double ga2$getSurfaceSecondary(int x, int z);

    @Invoker("getBand")
    BlockState ga2$getBand(int x, int y, int z);

    @Invoker("erodedBadlandsExtension")
    void ga2$erodedBadlandsExtension(BlockColumn column, int x, int z, int height, LevelHeightAccessor heights);

    @Invoker("frozenOceanExtension")
    void ga2$frozenOceanExtension(int minSurfaceLevel, Biome biome, BlockColumn column, BlockPos.MutableBlockPos pos, int x, int z, int height);
}
