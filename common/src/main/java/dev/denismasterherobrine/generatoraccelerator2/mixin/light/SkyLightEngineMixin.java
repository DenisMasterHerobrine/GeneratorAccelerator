package dev.denismasterherobrine.generatoraccelerator2.mixin.light;

import dev.denismasterherobrine.generatoraccelerator2.light.FastLight;
import dev.denismasterherobrine.generatoraccelerator2.light.Ga2LightEngine;
import dev.denismasterherobrine.generatoraccelerator2.light.Ga2Propagator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.lighting.SkyLightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sky light increase propagation. Uses vanilla's body with the lookups of {@link Ga2LightEngine}. Each neighbour costs
 * one section lookup. Vanilla's "is light stored here" and "what level is stored" checks fetch the same section twice.
 * No hook callbacks run inside the loop. Directions, checks, reads and writes match vanilla's, in the same order.
 */
@Mixin(value = SkyLightEngine.class, priority = 5000)
public abstract class SkyLightEngineMixin implements Ga2Propagator {
    @Unique
    private static final Direction[] GA2$DIRECTIONS = Direction.values();

    @Shadow @Final private BlockPos.MutableBlockPos mutablePos;

    @Shadow
    private int countEmptySectionsBelowIfAtBorder(long pos) {
        throw new AssertionError();
    }

    @Shadow
    private void propagateFromEmptySections(long pos, Direction direction, int level, boolean increase, int emptySections) {
        throw new AssertionError();
    }

    @Inject(method = "propagateIncrease", at = @At("HEAD"), cancellable = true)
    private void ga2$propagateIncreaseHook(long pos, long entry, int level, CallbackInfo ci) {
        if (FastLight.on) {
            this.ga2$propagateIncrease(pos, entry, level);
            ci.cancel();
        }
    }

    @Override
    public void ga2$propagateIncrease(long pos, long entry, int level) {
        Ga2LightEngine engine = (Ga2LightEngine) this;
        BlockState from = null;
        long section = SectionPos.blockToSection(pos);
        // Light data and blocks of the source position's own section. The light data is fetched again after a write to
        // that section, because the write may copy the layer.
        DataLayer sourceLayer = null;
        LevelChunkSection sourceBlocks = engine.ga2$blockSection(BlockPos.getX(pos), BlockPos.getY(pos), BlockPos.getZ(pos));
        int emptyBelow = this.countEmptySectionsBelowIfAtBorder(pos);
        for (Direction direction : GA2$DIRECTIONS) {
            if (!LightEngine.QueueEntry.shouldPropagateInDirection(entry, direction)) {
                continue;
            }
            long to = BlockPos.offset(pos, direction);
            long toSection = FastLight.neighbourSection(pos, section, direction);
            boolean same = toSection == section;
            DataLayer layer;
            if (same) {
                if (sourceLayer == null) {
                    sourceLayer = engine.ga2$layer(section);
                }
                layer = sourceLayer;
            } else {
                layer = engine.ga2$layer(toSection);
            }
            if (layer == null) {
                continue;
            }
            int stored = layer.get(BlockPos.getX(to) & 15, BlockPos.getY(to) & 15, BlockPos.getZ(to) & 15);
            if (level - 1 > stored) {
                this.mutablePos.set(to);
                BlockState state = same && sourceBlocks != null
                    ? (sourceBlocks.hasOnlyAir() ? Blocks.AIR.defaultBlockState() : sourceBlocks.getBlockState(BlockPos.getX(to) & 15, BlockPos.getY(to) & 15, BlockPos.getZ(to) & 15))
                    : engine.ga2$state(this.mutablePos);
                int lit = level - engine.ga2$opacity(state);
                if (lit > stored) {
                    if (from == null) {
                        from = LightEngine.QueueEntry.isFromEmptyShape(entry) ? Blocks.AIR.defaultBlockState() : engine.ga2$state(this.mutablePos.set(pos));
                    }
                    if (!engine.ga2$occludes(from, state, direction)) {
                        engine.ga2$setStoredLevel(to, toSection, lit);
                        if (same) {
                            sourceLayer = null;
                        }
                        if (lit > 1) {
                            engine.ga2$enqueueIncrease(to, LightEngine.QueueEntry.increaseSkipOneDirection(lit,
                                !state.canOcclude() || !state.useShapeForLightOcclusion(), direction.getOpposite()));
                        }
                        this.propagateFromEmptySections(to, direction, lit, true, emptyBelow);
                    }
                }
            }
        }
    }
}
