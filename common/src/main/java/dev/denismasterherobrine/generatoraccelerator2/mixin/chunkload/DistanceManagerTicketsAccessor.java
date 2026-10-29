package dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload;

import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ThrottlingChunkTaskDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(DistanceManager.class)
public interface DistanceManagerTicketsAccessor {
    @Accessor("ticketDispatcher")
    ThrottlingChunkTaskDispatcher ga2$ticketDispatcher();
}
