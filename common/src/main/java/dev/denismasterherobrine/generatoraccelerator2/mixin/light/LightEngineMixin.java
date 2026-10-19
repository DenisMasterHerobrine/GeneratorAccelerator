package dev.denismasterherobrine.generatoraccelerator2.mixin.light;

import dev.denismasterherobrine.generatoraccelerator2.light.FastLight;
import dev.denismasterherobrine.generatoraccelerator2.light.Ga2LightEngine;
import dev.denismasterherobrine.generatoraccelerator2.light.Ga2LightStorage;
import dev.denismasterherobrine.generatoraccelerator2.light.Ga2Propagator;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import net.minecraft.core.SectionPos;
import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Chunk lookup, block state reads and shape occlusion of the light engine ({@link FastLight}).
 *
 * <p>The chunk cache has {@link FastLight#CHUNK_SLOTS} direct-mapped slots. Vanilla caches only the last two chunks. As
 * in vanilla, the cache also remembers chunks that were not there. It is emptied where vanilla empties its own cache,
 * after every batch of updates. Each slot also records how the chunk's class reads a block state, so a read goes
 * straight to the section.
 */
@Mixin(value = LightEngine.class, priority = 5000)
public abstract class LightEngineMixin implements Ga2LightEngine {
    @Shadow @Final protected LightChunkGetter chunkSource;
    @Shadow @Final protected LayerLightSectionStorage<?> storage;

    @Shadow @Final private LongArrayFIFOQueue increaseQueue;

    @Shadow
    protected abstract int getOpacity(BlockState state, BlockPos pos);

    @Shadow
    protected abstract boolean shapeOccludes(long fromPos, BlockState from, long toPos, BlockState to, Direction direction);

    @Shadow
    protected abstract void enqueueIncrease(long pos, long entry);

    @Unique private final long[] ga2$chunkKeys = ga2$emptyKeys();
    @Unique private final LightChunk[] ga2$chunks = new LightChunk[FastLight.CHUNK_SLOTS];
    @Unique private final byte[] ga2$kinds = new byte[FastLight.CHUNK_SLOTS];
    @Unique private final LevelChunkSection[][] ga2$sections = new LevelChunkSection[FastLight.CHUNK_SLOTS][];
    @Unique private final int[] ga2$minY = new int[FastLight.CHUNK_SLOTS];

    @Unique
    private static long[] ga2$emptyKeys() {
        long[] keys = new long[FastLight.CHUNK_SLOTS];
        Arrays.fill(keys, ChunkPos.INVALID_CHUNK_POS);
        return keys;
    }

    /** The increase and decrease queues keep their arrays from batch to batch ({@link dev.denismasterherobrine.generatoraccelerator2.light.LightQueue}). */
    @org.spongepowered.asm.mixin.injection.Redirect(method = "<init>", require = 0,
        at = @At(value = "NEW", target = "()Lit/unimi/dsi/fastutil/longs/LongArrayFIFOQueue;"))
    private LongArrayFIFOQueue ga2$lightQueue() {
        return new dev.denismasterherobrine.generatoraccelerator2.light.LightQueue();
    }

    @Inject(method = "getChunk", at = @At("HEAD"), cancellable = true)
    private void ga2$getChunk(int chunkX, int chunkZ, CallbackInfoReturnable<LightChunk> cir) {
        if (FastLight.on) {
            cir.setReturnValue(this.ga2$chunks[this.ga2$slot(chunkX, chunkZ)]);
        }
    }

    /** Returns the chunk's slot. If the slot holds a different chunk, loads this chunk into it first. */
    @Unique
    private int ga2$slot(int chunkX, int chunkZ) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        int slot = FastLight.chunkSlot(chunkX, chunkZ);
        if (this.ga2$chunkKeys[slot] != key) {
            LightChunk chunk = this.chunkSource.getChunkForLighting(chunkX, chunkZ);
            this.ga2$chunkKeys[slot] = key;
            this.ga2$chunks[slot] = chunk;
            byte kind = chunk == null ? FastLight.KIND_OTHER : FastLight.kindOf(chunk);
            this.ga2$kinds[slot] = kind;
            if (kind != FastLight.KIND_OTHER) {
                this.ga2$sections[slot] = FastLight.sectionsOf(chunk);
                this.ga2$minY[slot] = chunk instanceof net.minecraft.world.level.chunk.ImposterProtoChunk imposter
                    ? imposter.getWrapped().getMinBuildHeight() : chunk.getMinBuildHeight();
            } else {
                this.ga2$sections[slot] = null;
            }
        }
        return slot;
    }

    @Override
    public BlockState ga2$state(BlockPos pos) {
        int x = pos.getX();
        int z = pos.getZ();
        int slot = this.ga2$slot(x >> 4, z >> 4);
        LightChunk chunk = this.ga2$chunks[slot];
        byte kind = this.ga2$kinds[slot];
        if (chunk == null) {
            return Blocks.BEDROCK.defaultBlockState();
        } else if (kind == FastLight.KIND_OTHER) {
            return chunk.getBlockState(pos);
        }
        return FastLight.blockState(kind, this.ga2$sections[slot], this.ga2$minY[slot], x, pos.getY(), z);
    }

    @Override
    public LevelChunkSection ga2$blockSection(int x, int y, int z) {
        int slot = this.ga2$slot(x >> 4, z >> 4);
        if (this.ga2$chunks[slot] == null || this.ga2$kinds[slot] == FastLight.KIND_OTHER) {
            return null;
        }
        LevelChunkSection[] sections = this.ga2$sections[slot];
        int minY = this.ga2$minY[slot];
        int index = (y >> 4) - (minY >> 4);
        return y >= minY && index >= 0 && index < sections.length ? sections[index] : null;
    }

    @Inject(method = "getState", at = @At("HEAD"), cancellable = true)
    private void ga2$getState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (FastLight.on) {
            cir.setReturnValue(this.ga2$state(pos));
        }
    }

    @Inject(method = "clearChunkCache", at = @At("RETURN"))
    private void ga2$clearChunkCache(CallbackInfo ci) {
        if (this.ga2$chunkKeys != null) {
            Arrays.fill(this.ga2$chunkKeys, ChunkPos.INVALID_CHUNK_POS);
            Arrays.fill(this.ga2$chunks, null);
            Arrays.fill(this.ga2$sections, null);
        }
    }

    /**
     * True when neither block has an occlusion shape. Vanilla then uses {@code Shapes.empty()} for both without asking
     * the blocks, and {@code faceShapeOccludes} of two empty shapes is false.
     */
    @Unique
    private static boolean ga2$noShapes(BlockState from, BlockState to) {
        return (!from.canOcclude() || !from.useShapeForLightOcclusion()) && (!to.canOcclude() || !to.useShapeForLightOcclusion());
    }

    @Inject(method = "shapeOccludes", at = @At("HEAD"), cancellable = true)
    private void ga2$shapeOccludes(long fromPos, BlockState from, long toPos, BlockState to, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (FastLight.on && ga2$noShapes(from, to)) {
            cir.setReturnValue(false);
        }
    }

    /**
     * Fast {@code propagateIncreases}. Runs vanilla's loop, but reads and writes the stored level through the fast
     * storage paths and calls the engine's fast {@code propagateIncrease} directly.
     */
    @Inject(method = "propagateIncreases", at = @At("HEAD"), cancellable = true)
    private void ga2$propagateIncreases(CallbackInfoReturnable<Integer> cir) {
        if (!FastLight.on || !(this instanceof Ga2Propagator propagator)) {
            return;
        }
        int count;
        for (count = 0; !this.increaseQueue.isEmpty(); count++) {
            long pos = this.increaseQueue.dequeueLong();
            long entry = this.increaseQueue.dequeueLong();
            int stored = this.ga2$layer(SectionPos.blockToSection(pos)).get(BlockPos.getX(pos) & 15, BlockPos.getY(pos) & 15, BlockPos.getZ(pos) & 15);
            int level = LightEngine.QueueEntry.getFromLevel(entry);
            if (LightEngine.QueueEntry.isIncreaseFromEmission(entry) && stored < level) {
                this.ga2$setStoredLevel(pos, level);
                stored = level;
            }
            if (stored == level) {
                propagator.ga2$propagateIncrease(pos, entry, stored);
            }
        }
        cir.setReturnValue(count);
    }

    @Override
    public DataLayer ga2$layer(long sectionKey) {
        return ((Ga2LightStorage) this.storage).ga2$layer(sectionKey);
    }

    @Override
    public int ga2$opacity(BlockState state, BlockPos pos) {
        return this.getOpacity(state, pos);
    }

    @Override
    public boolean ga2$occludes(long from, BlockState fromState, long to, BlockState toState, Direction direction) {
        return !ga2$noShapes(fromState, toState) && this.shapeOccludes(from, fromState, to, toState, direction);
    }

    @Override
    public void ga2$setStoredLevel(long pos, int level) {
        ((Ga2LightStorage) this.storage).ga2$setStoredLevel(pos, level);
    }

    @Override
    public void ga2$setStoredLevel(long pos, long sectionKey, int level) {
        ((Ga2LightStorage) this.storage).ga2$setStoredLevel(pos, sectionKey, level);
    }

    @Override
    public void ga2$enqueueIncrease(long pos, long entry) {
        this.enqueueIncrease(pos, entry);
    }
}
