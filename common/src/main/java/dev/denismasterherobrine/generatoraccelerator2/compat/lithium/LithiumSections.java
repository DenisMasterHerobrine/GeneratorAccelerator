package dev.denismasterherobrine.generatoraccelerator2.compat.lithium;

import dev.denismasterherobrine.generatoraccelerator2.compat.Reflect;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import javax.annotation.Nullable;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lithium's block counters per section ({@code util.block_tracking}, also in Radium and Canary). The mod counts, per section,
 * the blocks of each kind its entity code asks about ({@code countsByFlag}). The counters are filled when a section is built from
 * its palettes, kept up to date by {@code setBlockState}, and counted again from the palette when they are null. GA2's noise fill
 * writes a section's palette at once, without {@code setBlockState}. Such a section's counters are then dropped, and Lithium
 * counts them again the first time it asks. A section being generated has no listeners yet.
 */
public final class LithiumSections {
    private LithiumSections() {}

    @Nullable
    private static final MethodHandle CLEAR = clearer();
    /** Lithium's callback in {@code setBlockState} that updates the counters after a write, as merged into the section class. */
    @Nullable
    private static final MethodHandle TRACK = tracker();
    /** The callback info the callback is handed. It reads none of it, and the callback cannot cancel. */
    private static final CallbackInfoReturnable<BlockState> INFO = new CallbackInfoReturnable<>("setBlockState", false);

    /** True when the sections carry Lithium's counters. */
    static boolean tracked() {
        return CLEAR != null;
    }

    /** True when Lithium's counters are kept by its callback, which GA2's own section write then calls ({@link #afterSwap}). */
    static boolean trackerFound() {
        return TRACK != null;
    }

    /**
     * Runs Lithium's counter update after GA2's section write swapped {@code old} for {@code state}, where vanilla's method runs it.
     * Nothing without Lithium's callback.
     */
    public static void afterSwap(LevelChunkSection section, int x, int y, int z, BlockState state, boolean useLocks, BlockState old) {
        if (TRACK == null) {
            return;
        }
        try {
            TRACK.invokeExact(section, x, y, z, state, useLocks, INFO, old);
        } catch (Throwable t) {
            throw Reflect.unchecked(t);
        }
    }

    @Nullable
    private static MethodHandle tracker() {
        MethodHandle h = Reflect.mergedMethod(LevelChunkSection.class, "$updateFlagCounters", false, void.class, int.class, int.class, int.class,
            BlockState.class, boolean.class, CallbackInfoReturnable.class, BlockState.class);
        return h == null ? null : h.asType(MethodType.methodType(void.class, LevelChunkSection.class, int.class, int.class, int.class, BlockState.class,
            boolean.class, CallbackInfoReturnable.class, BlockState.class));
    }

    /** Drops {@code section}'s counters after GA2 wrote its blocks directly. Nothing without Lithium's counters. */
    public static void forgetCounts(LevelChunkSection section) {
        if (CLEAR == null) {
            return;
        }
        try {
            CLEAR.invokeExact(section, (short[]) null);
        } catch (Throwable t) {
            throw Reflect.unchecked(t);
        }
    }

    @Nullable
    private static MethodHandle clearer() {
        try {
            Field f = LevelChunkSection.class.getDeclaredField("countsByFlag");
            if (f.getType() != short[].class || Modifier.isStatic(f.getModifiers())) {
                return null;
            }
            f.setAccessible(true);
            return MethodHandles.lookup().unreflectSetter(f);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }
}
