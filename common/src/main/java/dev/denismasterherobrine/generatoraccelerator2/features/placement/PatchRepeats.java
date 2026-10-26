package dev.denismasterherobrine.generatoraccelerator2.features.placement;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.ref.LocalLongRef;
import dev.denismasterherobrine.generatoraccelerator2.chunkio.VerifyCounter;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.features.FeatureHooks;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * A random patch's tries at a position already tried ({@code RandomPatchFeature.place}, {@code RandomPatchRepeatsMixin}). A patch
 * places its inner feature at {@code tries} random positions around its origin, and the offsets are triangular: Stony Cliffs'
 * 512 tries over 15 by 15 columns, six patches per chunk, ask most columns several times. Each try there runs a heightmap, a
 * scan of up to 12 blocks down and a filter of 18 block tests, and most give no position. 7% of the workers' time in a 575-mod
 * pack.
 *
 * <p>When the inner feature's modifiers only read and draw nothing ({@link PlacementPipeline.KindsOf#readsOnly}), their positions
 * at a position depend only on the blocks. The patch's own code between two tries draws the next offsets and changes no block.
 * So a try at a position where they gave no position, with no feature run since, gives none again: the pipeline answers it
 * at once. Other mods' code around the pipeline ({@code PlacedFeature.place}) still runs. A try that reaches its feature, or that
 * the pipeline does not see, makes every earlier answer of the patch stale. While no other mod changes the patch's method (hook
 * {@code features.patchRepeats}). {@code -Dgeneratoraccelerator2.patchRepeats=false} turns it off.
 *
 * <p>{@code -Dgeneratoraccelerator2.verify.patchRepeats=N}: every N-th such try runs the pipeline anyway and checks that it gives
 * no position.
 */
public final class PatchRepeats {
    private PatchRepeats() {}

    public static final boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.patchRepeats"));
    private static final HookTargets.Hook HOOK = HookTargets.hook(HookTargets.PATCH_REPEATS);
    static final VerifyCounter VERIFY = new VerifyCounter("patchRepeats");

    /** Tries of patches whose inner feature only reads, and those answered without running its modifiers. Counted per thread, added when a top-level placement ends. */
    public static final LongAdder TRIED = new LongAdder(), SKIPPED = new LongAdder();

    /** {@code inner.place(level, generator, random, pos)} for a try of the patch whose stamp is {@code stamp}. */
    public static boolean place(PlacedFeature inner, WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos pos,
                                Operation<Boolean> original, LocalLongRef stamp) {
        if (!ENABLED || !FeatureHooks.enabled() || !HOOK.allowed() || !PlacementPipeline.readsOnly(inner)) {
            return original.call(inner, level, generator, random, pos);
        }
        return PlacementPipeline.patchTry(inner, level, generator, random, pos, stamp);
    }
}
