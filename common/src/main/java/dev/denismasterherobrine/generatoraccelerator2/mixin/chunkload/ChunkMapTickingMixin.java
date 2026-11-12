package dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload;

import dev.denismasterherobrine.generatoraccelerator2.chunkload.TickingHolders;
import it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** The chunk map's set of block ticking holders ({@link TickingHolders}). */
@Mixin(ChunkMap.class)
public abstract class ChunkMapTickingMixin implements TickingHolders.Owner {
    @Unique
    private ReferenceLinkedOpenHashSet<ChunkHolder> ga2$ticking;

    @Override
    public ReferenceLinkedOpenHashSet<ChunkHolder> ga2$tickingHolders() {
        ReferenceLinkedOpenHashSet<ChunkHolder> set = this.ga2$ticking;
        if (set == null) {
            // made on first use (server thread). An instance initialiser in a mixin can get lost (javac 25)
            this.ga2$ticking = set = new ReferenceLinkedOpenHashSet<>();
        }
        return set;
    }
}
