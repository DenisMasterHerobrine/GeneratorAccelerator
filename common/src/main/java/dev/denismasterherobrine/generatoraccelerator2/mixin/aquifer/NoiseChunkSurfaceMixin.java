package dev.denismasterherobrine.generatoraccelerator2.mixin.aquifer;

import dev.denismasterherobrine.generatoraccelerator2.aquifer.SurfaceLevelCache;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.DfcHooks;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.Ga2NoiseChunk;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.SurfaceLevels;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ColumnPos;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Shares {@code computePreliminarySurfaceLevel} results between chunks through
 * {@link SurfaceLevelCache}. The chunk's own memo map is untouched. Only misses in that map consult
 * the shared table. Applied late (high priority value) so the mixin plugin sees other mods' changes to
 * these methods before this one is applied.
 */
@Mixin(value = NoiseChunk.class, priority = 5000)
public abstract class NoiseChunkSurfaceMixin {

    @Unique
    private SurfaceLevelCache ga2$surfaceLevels;

    @Shadow @Final private Long2IntMap preliminarySurfaceLevelCache;

    @Shadow
    private int computePreliminarySurfaceLevel(long column) {
        throw new AssertionError();
    }

    /**
     * Vanilla's {@code preliminarySurfaceLevel} without the method reference that vanilla's {@code computeIfAbsent} allocates on
     * every call. The aquifer calls this method for each fluid status it computes. Same memo, looked up once. On a miss, the same
     * computation runs and its result is stored the same way. The {@code getOrDefault} sentinel is never a real level: a
     * {@code find_top_surface} returns a y between its bounds. Copied over vanilla's body when no other mod
     * changes that body ({@code compat.LateOverwrites}).
     */
    @Unique
    private int ga2$preliminarySurfaceLevel(int x, int z) {
        int i = QuartPos.toBlock(QuartPos.fromBlock(x));
        int j = QuartPos.toBlock(QuartPos.fromBlock(z));
        long column = ColumnPos.asLong(i, j);
        Long2IntMap memo = this.preliminarySurfaceLevelCache;
        int level = memo.getOrDefault(column, Integer.MIN_VALUE);
        if (level == Integer.MIN_VALUE) {
            level = this.computePreliminarySurfaceLevel(column);
            memo.put(column, level);
        }
        return level;
    }

    @Unique
    private boolean ga2$surfaceLevelsSet;

    /** Before the constructor makes the aquifer, which reads the level of every column it covers (26.1's {@code skipSamplingAboveY}). */
    @Inject(method = "<init>", at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER,
        target = "Lnet/minecraft/world/level/levelgen/NoiseChunk;preliminarySurfaceLevel:Lnet/minecraft/world/level/levelgen/DensityFunction;"),
        require = 0)
    private void ga2$surfaceLevels(int cellCountXZ, RandomState randomState, int firstBlockX, int firstBlockZ, NoiseSettings noiseSettings,
                                   DensityFunctions.BeardifierOrMarker beardifier, NoiseGeneratorSettings settings, Aquifer.FluidPicker fluidPicker,
                                   Blender blender, CallbackInfo ci) {
        this.ga2$surfaceLevels = SurfaceLevelCache.forChunk(randomState, noiseSettings, blender);
        this.ga2$surfaceLevelsSet = true;
    }

    /** When another mod's change to the constructor hid the field write above. */
    @Inject(method = "<init>", at = @At("TAIL"))
    private void ga2$surfaceLevelsLate(int cellCountXZ, RandomState randomState, int firstBlockX, int firstBlockZ, NoiseSettings noiseSettings,
                                       DensityFunctions.BeardifierOrMarker beardifier, NoiseGeneratorSettings settings, Aquifer.FluidPicker fluidPicker,
                                       Blender blender, CallbackInfo ci) {
        if (!this.ga2$surfaceLevelsSet) {
            this.ga2$surfaceLevels = SurfaceLevelCache.forChunk(randomState, noiseSettings, blender);
        }
    }

    @Inject(method = "computePreliminarySurfaceLevel", at = @At("HEAD"), cancellable = true)
    private void ga2$sharedSurfaceLevel(long column, CallbackInfoReturnable<Integer> cir) {
        SurfaceLevelCache cache = this.ga2$surfaceLevels;
        if (cache != null) {
            int level = cache.get(column);
            if (level != SurfaceLevelCache.MISS) {
                cir.setReturnValue(level);
                return;
            }
        }
        // On a miss, run vanilla's walk on the compiled surface level function, if the chunk has one bound.
        SurfaceLevels program = (Object) this instanceof Ga2NoiseChunk f
            ? f.ga2$levelProgram() : null;
        if (program != null && DfcHooks.enabled()) {
            int level = program.level(ColumnPos.getX(column), ColumnPos.getZ(column));
            if (cache != null) {
                cache.put(column, level);
            }
            cir.setReturnValue(level);
        }
    }

    @Inject(method = "computePreliminarySurfaceLevel", at = @At("RETURN"))
    private void ga2$shareSurfaceLevel(long column, CallbackInfoReturnable<Integer> cir) {
        SurfaceLevelCache cache = this.ga2$surfaceLevels;
        if (cache != null) {
            cache.put(column, cir.getReturnValueI());
        }
    }
}
