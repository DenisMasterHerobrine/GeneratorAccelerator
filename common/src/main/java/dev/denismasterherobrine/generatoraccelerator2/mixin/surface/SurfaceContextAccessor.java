package dev.denismasterherobrine.generatoraccelerator2.mixin.surface;

import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** What vanilla's rule context holds at the block its loop is at, read by the compiled rules in that loop ({@code surface.LoopRules}). */
@Mixin(SurfaceRules.Context.class)
public interface SurfaceContextAccessor {
    @Accessor("system")
    SurfaceSystem ga2$system();

    @Accessor("randomState")
    RandomState ga2$randomState();

    @Accessor("chunk")
    ChunkAccess ga2$chunk();

    @Accessor("context")
    WorldGenerationContext ga2$context();

    @Accessor("lastUpdateXZ")
    long ga2$lastUpdateXZ();

    @Accessor("lastUpdateY")
    long ga2$lastUpdateY();

    @Accessor("blockX")
    int ga2$blockX();

    @Accessor("blockZ")
    int ga2$blockZ();

    @Accessor("blockY")
    int ga2$blockY();

    @Accessor("surfaceDepth")
    int ga2$surfaceDepth();

    @Accessor("waterHeight")
    int ga2$waterHeight();

    @Accessor("stoneDepthAbove")
    int ga2$stoneDepthAbove();

    @Accessor("stoneDepthBelow")
    int ga2$stoneDepthBelow();

    @Accessor("biome")
    Supplier<Holder<Biome>> ga2$biome();

    @Invoker("getMinSurfaceLevel")
    int ga2$minSurfaceLevel();
}
