package dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload;

import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.server.level.DistanceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(DistanceManager.class)
public interface DistanceManagerTicketsAccessor {
    @Accessor("ticketThrottler")
    ChunkTaskPriorityQueueSorter ga2$ticketThrottler();
}
