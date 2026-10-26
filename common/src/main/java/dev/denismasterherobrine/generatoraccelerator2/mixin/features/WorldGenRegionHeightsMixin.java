package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.features.region.Ga2Region;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * The region's {@code getHeight(type, x, z)} through the region's chunk lookup for GA2 ({@code Ga2Region.ga2$chunk}). Vanilla
 * resolves the chunk through {@code getChunk(x >> 4, z >> 4)} at every call: the dependency grid, then the chunk holder's
 * future. Heightmap placements ask it for every position, a random patch thousands of times per chunk. The lookup for GA2 gives
 * the same chunk ({@code getChunk(cx, cz, EMPTY, true)}, kept for the task by the region chunk cache). Put in place once the
 * class is complete, when no other mod changed {@code getHeight} ({@code compat.LateOverwrites}). Another mod's change to it or
 * to {@code getChunk(int, int)} is recorded ({@code features.regionHeights}). In that case vanilla's lookup answers.
 */
@Mixin(value = WorldGenRegion.class, priority = 5000)
public abstract class WorldGenRegionHeightsMixin {
    @Unique
    private static final HookTargets.Hook GA2$HOOK = HookTargets.hook(HookTargets.REGION_HEIGHTS);

    /** The body of {@code getHeight(type, x, z)}. */
    @Unique
    private int ga2$getHeight(Heightmap.Types type, int x, int z) {
        int cx = SectionPos.blockToSectionCoord(x), cz = SectionPos.blockToSectionCoord(z);
        ChunkAccess chunk = GA2$HOOK.allowed() ? ((Ga2Region) this).ga2$chunk(cx, cz) : ((WorldGenRegion) (Object) this).getChunk(cx, cz);
        return chunk.getHeight(type, x & 15, z & 15) + 1;
    }
}
