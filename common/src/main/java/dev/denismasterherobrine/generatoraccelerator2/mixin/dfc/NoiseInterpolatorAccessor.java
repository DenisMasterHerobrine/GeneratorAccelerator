package dev.denismasterherobrine.generatoraccelerator2.mixin.dfc;

import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(NoiseChunk.NoiseInterpolator.class)
public interface NoiseInterpolatorAccessor {
    @Accessor("slice0") double[][] ga2$slice0();
    @Accessor("slice1") double[][] ga2$slice1();
    @Accessor("slice0") void ga2$setSlice0(double[][] slice);
    @Accessor("slice1") void ga2$setSlice1(double[][] slice);
    @Accessor("noise000") double ga2$noise000();
    @Accessor("noise100") double ga2$noise100();
    @Accessor("noise010") double ga2$noise010();
    @Accessor("noise110") double ga2$noise110();
    @Accessor("noise001") double ga2$noise001();
    @Accessor("noise101") double ga2$noise101();
    @Accessor("noise011") double ga2$noise011();
    @Accessor("noise111") double ga2$noise111();

    /** Loads the cell's eight corners from the slices, as {@code NoiseChunk.selectCellYZ} does. */
    @Invoker("selectCellYZ") void ga2$selectCellYZ(int cellY, int cellZ);
}
