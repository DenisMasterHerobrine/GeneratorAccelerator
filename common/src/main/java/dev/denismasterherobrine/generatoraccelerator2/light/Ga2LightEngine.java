package dev.denismasterherobrine.generatoraccelerator2.light;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;

/**
 * What the light propagation loops ({@code mixin/light/SkyLightEngineMixin}, {@code BlockLightEngineMixin}) need from
 * {@code LightEngine}, implemented by {@code LightEngineMixin}. Each method answers exactly what the vanilla method named
 * in its comment answers, without the hooks' callback objects.
 */
public interface Ga2LightEngine {
    /** {@code storage.getDataLayer(sectionKey, true)}. Null when the section stores no light. */
    @Nullable
    DataLayer ga2$layer(long sectionKey);

    /** {@code getState(pos)}. */
    BlockState ga2$state(BlockPos pos);

    /**
     * The section {@code getState} reads for blocks of this block's section. Returned when the chunk is a plain proto or
     * level chunk and the block is inside the chunk's sections. In that case {@code getState} is
     * {@code section.hasOnlyAir() ? AIR : section.getBlockState(...)}. Null otherwise.
     */
    @Nullable
    net.minecraft.world.level.chunk.LevelChunkSection ga2$blockSection(int x, int y, int z);

    /** {@code getOpacity(state, pos)}. */
    int ga2$opacity(BlockState state, BlockPos pos);

    /** {@code shapeOccludes(from, fromState, to, toState, direction)}. */
    boolean ga2$occludes(long from, BlockState fromState, long to, BlockState toState, Direction direction);

    /** {@code storage.setStoredLevel(pos, level)}. */
    void ga2$setStoredLevel(long pos, int level);

    /** {@code storage.setStoredLevel(pos, level)} for a block of section {@code sectionKey} ({@code SectionPos.blockToSection(pos)}). */
    void ga2$setStoredLevel(long pos, long sectionKey, int level);

    /** {@code enqueueIncrease(pos, entry)}. */
    void ga2$enqueueIncrease(long pos, long entry);
}
