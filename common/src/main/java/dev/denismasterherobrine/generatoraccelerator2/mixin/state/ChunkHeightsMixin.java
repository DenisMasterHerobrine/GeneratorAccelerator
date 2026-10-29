package dev.denismasterherobrine.generatoraccelerator2.mixin.state;

import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * {@code ChunkAccess.getMinY} and {@code getHeight} ask the chunk's height accessor on every call. For a level, the
 * accessor reads its dimension type through the type's holder. Every section index, build height check and biome read of a
 * chunk calls these methods, some several times. The accessor is final and its build height is fixed for its life. So both
 * values are read once per chunk and kept in one {@code long}, written whole. Put in place once the class is complete, when no
 * other mod changed the two methods ({@code compat.LateOverwrites}, hook {@code state.chunkHeights}).
 */
@Mixin(value = ChunkAccess.class, priority = 5000)
public abstract class ChunkHeightsMixin {
    @Shadow
    @Final
    protected LevelHeightAccessor levelHeightAccessor;

    /** Min build height in the high half and height in the low half. 0 until first read, since no build height is empty. */
    @Unique
    private long ga2$heights;

    @Unique
    private long ga2$heights() {
        long h = this.ga2$heights;
        if (h == 0L) {
            h = (long) this.levelHeightAccessor.getMinY() << 32 | this.levelHeightAccessor.getHeight() & 0xFFFFFFFFL;
            this.ga2$heights = h;
        }
        return h;
    }

    /** The body of {@code getMinY()}: the accessor's answer, read once. */
    @Unique
    public int ga2$getMinY() {
        return (int) (ga2$heights() >> 32);
    }

    /** The body of {@code getHeight()}: the accessor's answer, read once. */
    @Unique
    public int ga2$getHeight() {
        return (int) ga2$heights();
    }
}
