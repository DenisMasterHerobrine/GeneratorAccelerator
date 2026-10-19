package dev.denismasterherobrine.generatoraccelerator2.mixin.scheduling;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The chunk map's holders, for {@code /ga2 steps holders}. */
@Mixin(ChunkMap.class)
public interface ChunkMapHoldersAccessor {
    @Accessor("updatingChunkMap") Long2ObjectLinkedOpenHashMap<ChunkHolder> ga2$updatingChunkMap();

    @Accessor("pendingUnloads") Long2ObjectLinkedOpenHashMap<ChunkHolder> ga2$pendingUnloads();
}
