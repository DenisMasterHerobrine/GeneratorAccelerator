package dev.denismasterherobrine.generatoraccelerator2.dfc.runtime;

import dev.denismasterherobrine.generatoraccelerator2.beardifier.YungPieces;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.Reflect;
import dev.denismasterherobrine.generatoraccelerator2.mixin.dfc.NoiseChunkAccessor;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Other mods' changes to the block a NoiseChunk places ({@code NoiseChunk.getInterpolatedState}) that FastFill runs itself. Each
 * change acts only in some chunks, and there only on water and lava. It returns from the method with another block:
 * <ul>
 * <li>YUNG's API turns the fluids its structure pieces marked into other blocks, in chunks its pieces reach.</li>
 * <li>Iron's Spellbooks turns water and lava within 5 blocks of a tracked structure's pieces into cave air or the default
 *     block, in chunks holding such a structure's start. Iron's records this on the NoiseChunk when the generator makes
 *     the NoiseChunk.</li>
 * </ul>
 * A chunk where no change acts keeps FastFill's own per-block computation. Where exactly one acts, FastFill runs that mod's
 * handler on each water and lava block, as the merged method would. A handler that does nothing in a chunk changes nothing
 * there, so the other handlers' order and cancellation cannot matter. A chunk where two act asks the NoiseChunk for every
 * block. So does any chunk while another mod changes the method.
 */
public final class FillSteps {
    private FillSteps() {}


    /** A known change: whether it acts in a chunk, and its handler (a {@code (NoiseChunk, CallbackInfoReturnable)} handle, or null). */
    private record Known(String mixin, Predicate<NoiseChunk> acts, MethodHandle handler) {}

    /** The plan of a chunk where nothing acts, or the method is unchanged: FastFill computes every block. */
    public static final Object INLINE = new Object();
    /** The plan of a chunk FastFill must ask the NoiseChunk for every block. */
    public static final Object SLOW = new Object();

    private static final HookTargets.Hook STATE = HookTargets.hook(HookTargets.NOISE_FILL_STATE);
    private static volatile List<Known> known;
    private static volatile boolean resolved;

    /**
     * How FastFill computes the blocks of this chunk: {@link #INLINE}, {@link #SLOW}, or the {@link MethodHandle} of the one
     * handler to run on water and lava ({@link #run}).
     */
    public static Object plan(NoiseChunk chunk) {
        if (STATE.allowed()) {
            return INLINE;
        }
        List<Known> steps = known();
        if (steps == null) {
            return SLOW;
        }
        Known acting = null;
        for (Known k : steps) {
            if (k.acts().test(chunk)) {
                if (acting != null || k.handler() == null) {
                    return SLOW;
                }
                acting = k;
            }
        }
        return acting == null ? INLINE : acting.handler();
    }

    /** {@code state} after {@code handler} runs at the NoiseChunk's current block, when it is water or lava. Others never change. */
    @SuppressWarnings("unchecked")
    public static BlockState run(MethodHandle handler, NoiseChunk chunk, BlockState state) {
        if (state == null || !state.is(Blocks.WATER) && !state.is(Blocks.LAVA)) {
            return state;
        }
        CallbackInfoReturnable<BlockState> cir = new CallbackInfoReturnable<>("getInterpolatedState", true, state);
        try {
            handler.invoke(chunk, cir);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
        return cir.getReturnValue();
    }

    /** True when this class knows every change to the method. The GPU path sends chunks where a known change acts to the CPU. */
    public static boolean allKnown() {
        return STATE.allowed() || known() != null;
    }

    /** True when some known change acts in this chunk (or the changes are not all known). */
    public static boolean actsIn(NoiseChunk chunk) {
        if (STATE.allowed()) {
            return false;
        }
        List<Known> steps = known();
        if (steps == null) {
            return true;
        }
        for (Known k : steps) {
            if (k.acts().test(chunk)) {
                return true;
            }
        }
        return false;
    }

    /**
     * For a chunk the GPU would fill, which then gets no NoiseChunk of its own. Returns whether a known change may act there, so
     * the CPU fills the chunk. YUNG's acts where its pieces reach the chunk ({@code beardifier} holds them). Iron's acts where the
     * chunk holds a start of one of its tracked structures. Iron's mixin checks that condition when the generator makes the chunk's
     * NoiseChunk. Here it is asked of Iron's own helper and config. A change that is not known, or a check that cannot run, reads
     * as acting.
     */
    public static boolean mayActForGpu(net.minecraft.world.level.StructureManager structures, net.minecraft.world.level.ChunkPos pos,
                                       net.minecraft.world.level.levelgen.DensityFunctions.BeardifierOrMarker beardifier) {
        return mayActForGpu(structures, pos, beardifier, false);
    }

    /**
     * As {@link #mayActForGpu(net.minecraft.world.level.StructureManager, net.minecraft.world.level.ChunkPos, net.minecraft.world.level.levelgen.DensityFunctions.BeardifierOrMarker)}.
     * With {@code yungMaskOnGpu}, YUNG's step does not count: the GPU computes its mask and its replacement with the chunk's enhanced
     * pieces ({@code beardifier.EnhancedBeard}).
     */
    public static boolean mayActForGpu(net.minecraft.world.level.StructureManager structures, net.minecraft.world.level.ChunkPos pos,
                                       net.minecraft.world.level.levelgen.DensityFunctions.BeardifierOrMarker beardifier, boolean yungMaskOnGpu) {
        if (STATE.allowed()) {
            return false;
        }
        List<Known> steps = known();
        if (steps == null) {
            return true;
        }
        for (Known k : steps) {
            if (k.mixin().equals(YungPieces.NOISE_CHUNK_MIXIN) ? !yungMaskOnGpu && !YungPieces.none(beardifier) : ironsStructures(structures, pos)) {
                return true;
            }
        }
        return false;
    }

    private static volatile MethodHandle ironsTracked;
    // Iron's Spellbooks is a NeoForge mod. Its config is a NeoForge ModConfigSpec, read reflectively (isLoaded(), and get() of the value)
    private static volatile MethodHandle ironsSpecLoaded;
    private static volatile MethodHandle ironsDetection;
    private static volatile boolean ironsResolved;

    /** Iron's Spellbooks' {@code NoiseChunkGeneratorMixin.detectAquifers} condition: detection on and a tracked structure's start in the chunk. */
    private static boolean ironsStructures(net.minecraft.world.level.StructureManager structures, net.minecraft.world.level.ChunkPos pos) {
        if (!ironsResolved) {
            synchronized (FillSteps.class) {
                if (!ironsResolved) {
                    try {
                        ClassLoader loader = FillSteps.class.getClassLoader();
                        Class<?> helper = Class.forName("io.redspace.ironsspellbooks.worldgen.AquiferHelper", true, loader);
                        ironsTracked = MethodHandles.publicLookup().unreflect(helper.getMethod("getOrCacheStructures", net.minecraft.world.level.StructureManager.class));
                        Class<?> configs = Class.forName("io.redspace.ironsspellbooks.config.ServerConfigs", true, loader);
                        Object spec = configs.getField("SPEC").get(null);
                        Object detection = configs.getField("AQUIFER_DETECTION").get(null);
                        ironsSpecLoaded = MethodHandles.publicLookup().unreflect(spec.getClass().getMethod("isLoaded")).bindTo(spec);
                        ironsDetection = MethodHandles.publicLookup().unreflect(detection.getClass().getMethod("get")).bindTo(detection);
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                        ironsTracked = null;
                    }
                    ironsResolved = true;
                }
            }
        }
        MethodHandle tracked = ironsTracked;
        if (tracked == null) {
            return true;
        }
        try {
            if ((boolean) ironsSpecLoaded.invoke() && !Boolean.TRUE.equals(ironsDetection.invoke())) {
                return false;
            }
            java.util.Set<?> set = (java.util.Set<?>) tracked.invoke(structures);
            return !structures.startsForStructure(pos, set::contains).isEmpty();
        } catch (Throwable t) {
            return true;
        }
    }

    /** The known changes of the method, in no particular order, or null when some change is not known. */
    private static List<Known> known() {
        if (!resolved) {
            synchronized (FillSteps.class) {
                if (!resolved) {
                    known = resolve(HookTargets.foreign(HookTargets.NOISE_FILL_STATE));
                    resolved = true;
                }
            }
        }
        return known;
    }

    private static List<Known> resolve(List<String> foreign) {
        List<Known> out = new ArrayList<>();
        for (String mixin : foreign) {
            if (mixin.equals(YungPieces.NOISE_CHUNK_MIXIN)) {
                out.add(new Known(mixin, chunk -> !YungPieces.none(((NoiseChunkAccessor) (Object) chunk).ga2$beardifier()),
                    handler("$yungsapi_dontFillMarkedPositions")));
            } else if (mixin.equals(HookTargets.IRONS_NOISE_CHUNK)) {
                MethodHandle status = getter("irons_spellbooks$getAquifierStatus");
                if (status == null) {
                    return null;
                }
                out.add(new Known(mixin, chunk -> {
                    try {
                        return status.invoke(chunk) != null;
                    } catch (Throwable t) {
                        return true;
                    }
                }, handler("irons_spellbooks$cancelAquifierGeneration")));
            } else {
                return null;
            }
        }
        return out;
    }

    /** The NoiseChunk's handler method whose name ends with {@code suffix} and takes only a CallbackInfoReturnable, or null. */
    private static MethodHandle handler(String suffix) {
        for (Method m : Reflect.declaredMethods(NoiseChunk.class)) {
            if (m.getName().endsWith(suffix) && m.getReturnType() == void.class
                && java.util.Arrays.equals(m.getParameterTypes(), new Class<?>[] {CallbackInfoReturnable.class})) {
                try {
                    m.setAccessible(true);
                    return MethodHandles.lookup().unreflect(m);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /** The NoiseChunk's no-argument method {@code name} (a mixin's added interface method), or null. */
    private static MethodHandle getter(String name) {
        for (Method m : Reflect.declaredMethods(NoiseChunk.class)) {
            if (m.getName().equals(name) && m.getParameterCount() == 0 && m.getReturnType() != void.class) {
                try {
                    m.setAccessible(true);
                    return MethodHandles.lookup().unreflect(m);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return null;
                }
            }
        }
        return null;
    }
}
