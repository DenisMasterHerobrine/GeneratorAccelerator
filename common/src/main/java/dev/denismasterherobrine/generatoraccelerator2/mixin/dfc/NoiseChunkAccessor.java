package dev.denismasterherobrine.generatoraccelerator2.mixin.dfc;

import java.util.List;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseRouter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(NoiseChunk.class)
public interface NoiseChunkAccessor {
    @Accessor("cellCountY") int ga2$cellCountY();
    @Accessor("cellNoiseMinY") int ga2$cellNoiseMinY();
    @Accessor("cellHeight") int ga2$cellHeight();
    @Accessor("cellWidth") int ga2$cellWidth();
    @Accessor("cellCountXZ") int ga2$cellCountXZ();
    @Accessor("cellStartBlockX") int ga2$cellStartBlockX();
    @Accessor("cellStartBlockY") int ga2$cellStartBlockY();
    @Accessor("cellStartBlockZ") int ga2$cellStartBlockZ();
    @Accessor("interpolators") List<NoiseChunk.NoiseInterpolator> ga2$interpolators();
    /** Elements are {@code NoiseChunk.CacheAllInCell}, which implements {@code DensityFunctions.MarkerOrMarked}. */
    @Accessor("cellCaches") List<DensityFunction> ga2$cellCaches();
    @Accessor("beardifier") DensityFunctions.BeardifierOrMarker ga2$beardifier();
    /** The chunk's preliminary surface level memo, keyed by quart-aligned {@code ColumnPos}. */
    @Accessor("preliminarySurfaceLevel") Long2IntMap ga2$surfaceLevelMemo();
    @Invoker("wrap") DensityFunction ga2$wrap(DensityFunction fn);
    @Invoker("cachedClimateSampler") Climate.Sampler ga2$cachedClimateSampler(NoiseRouter router, List<Climate.ParameterPoint> spawnTarget);
    @Invoker("getInterpolatedState") BlockState ga2$getInterpolatedState();
}
