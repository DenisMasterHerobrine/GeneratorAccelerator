package dev.denismasterherobrine.generatoraccelerator2.parallellight.mixin;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The parts of {@code LightEngine.runLightUpdates} the parallel propagation runs or replaces. */
@Mixin(LightEngine.class)
public interface LightEngineAccessor {
    @Accessor("chunkSource")
    LightChunkGetter ga2pl$chunkSource();

    @Accessor("storage")
    LayerLightSectionStorage<?> ga2pl$storage();

    @Accessor("blockNodesToCheck")
    LongOpenHashSet ga2pl$blockNodesToCheck();

    @Accessor("decreaseQueue")
    LongArrayFIFOQueue ga2pl$decreaseQueue();

    @Accessor("increaseQueue")
    LongArrayFIFOQueue ga2pl$increaseQueue();

    @Invoker("checkNode")
    void ga2pl$checkNode(long pos);

    @Invoker("propagateDecreases")
    int ga2pl$propagateDecreases();

    @Invoker("propagateIncreases")
    int ga2pl$propagateIncreases();

    @Invoker("clearChunkCache")
    void ga2pl$clearChunkCache();
}
