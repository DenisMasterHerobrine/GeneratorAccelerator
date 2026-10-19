package dev.denismasterherobrine.generatoraccelerator2.mixin.structure;

import dev.denismasterherobrine.generatoraccelerator2.mixin.scheduling.ChunkAccessAccessor;
import dev.denismasterherobrine.generatoraccelerator2.structure.StructureReferences;
import java.util.Map;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.DebugPackets;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * {@code createReferences} reading the structure starts of a plain proto chunk in place. Vanilla's {@code getAllStarts().values()}
 * made an unmodifiable map, a values view and two iterators at each of the 289 chunks around every chunk. Nearly all of those
 * chunks have no start. The chunks, starts, order and calls are unchanged. Copied over vanilla's body when no other mod changes it
 * ({@code compat.LateOverwrites}).
 */
@Mixin(value = ChunkGenerator.class, priority = 5000)
public abstract class StructureReferencesMixin {
    @Unique
    private void ga2$createReferences(WorldGenLevel level, StructureManager structureManager, ChunkAccess chunk) {
        ChunkPos chunkpos = chunk.getPos();
        int j = chunkpos.x;
        int k = chunkpos.z;
        int l = chunkpos.getMinBlockX();
        int i1 = chunkpos.getMinBlockZ();
        SectionPos sectionpos = SectionPos.bottomOf(chunk);
        for (int j1 = j - 8; j1 <= j + 8; j1++) {
            for (int k1 = k - 8; k1 <= k + 8; k1++) {
                ChunkAccess source = level.getChunk(j1, k1);
                Map<Structure, StructureStart> starts = source.getClass() == ProtoChunk.class ? ((ChunkAccessAccessor) source).ga2$structureStarts()
                    : source.getAllStarts();
                if (starts.isEmpty()) {
                    continue;
                }
                long l1 = ChunkPos.asLong(j1, k1);
                for (StructureStart structurestart : starts.values()) {
                    try {
                        if (structurestart.isValid() && structurestart.getBoundingBox().intersects(l, i1, l + 15, i1 + 15)) {
                            structureManager.addReferenceForStructure(sectionpos, structurestart.getStructure(), l1, chunk);
                            DebugPackets.sendStructurePacket(level, structurestart);
                        }
                    } catch (Exception exception) {
                        throw StructureReferences.crash(level, structurestart, exception);
                    }
                }
            }
        }
    }
}
