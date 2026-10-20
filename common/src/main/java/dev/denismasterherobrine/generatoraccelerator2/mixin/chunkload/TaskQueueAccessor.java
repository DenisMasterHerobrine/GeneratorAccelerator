package dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ChunkTaskPriorityQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkTaskPriorityQueue.class)
public interface TaskQueueAccessor {
    @Accessor("acquired")
    LongSet ga2$acquired();

    @Accessor("maxTasks")
    int ga2$maxTasks();
}
