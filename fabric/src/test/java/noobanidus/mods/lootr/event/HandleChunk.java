package noobanidus.mods.lootr.event;

import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/** Lootr 0.7's loaded chunks that GA2 reads ({@code compat.lootr.LootrEntries}), for tests. */
public class HandleChunk {
    public static final Map<ResourceKey<Level>, Set<ChunkPos>> LOADED_CHUNKS = Collections.synchronizedMap(new Object2ObjectLinkedOpenHashMap<>());
}
