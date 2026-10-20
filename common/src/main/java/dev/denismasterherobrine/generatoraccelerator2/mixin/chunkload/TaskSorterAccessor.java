package dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload;

import java.util.Map;
import net.minecraft.server.level.ChunkTaskPriorityQueue;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.util.thread.ProcessorHandle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkTaskPriorityQueueSorter.class)
public interface TaskSorterAccessor {
    @Accessor("queues")
    Map<ProcessorHandle<?>, ChunkTaskPriorityQueue<?>> ga2$queues();
}
