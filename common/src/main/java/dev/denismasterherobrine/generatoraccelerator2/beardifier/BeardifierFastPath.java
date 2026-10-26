package dev.denismasterherobrine.generatoraccelerator2.beardifier;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.compat.MixinTargets;
import dev.denismasterherobrine.generatoraccelerator2.dfc.DfcConfig;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.DfcHooks;
import net.minecraft.world.level.levelgen.Beardifier;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;

/**
 * Decides, once per {@code Beardifier}, whether the piece index runs for it (the work itself is in
 * {@code BeardifierMixin}). Independent of the density function compiler, so it also speeds up the
 * vanilla per-block fill when the compiler's NoiseChunk hooks are off for compatibility.
 */
public final class BeardifierFastPath {
    private BeardifierFastPath() {}

    /** Test hook: null follows the config, otherwise forces the index on or off for beardifiers created afterwards. */
    public static volatile Boolean override;

    /** {@code -Dgeneratoraccelerator2.verify.beardifier=N}: every N-th sum of the index is checked against vanilla's {@code compute}. */
    private static final int VERIFY_EVERY = Integer.getInteger("generatoraccelerator2.verify.beardifier", 0);
    private static final AtomicLong SEEN = new AtomicLong();
    public static final AtomicLong VERIFIED = new AtomicLong();
    public static final AtomicLong DIFFERING = new AtomicLong();
    private static final ThreadLocal<boolean[]> VANILLA = ThreadLocal.withInitial(() -> new boolean[1]);

    public static boolean sampleVerify() {
        return VERIFY_EVERY > 0 && !VANILLA.get()[0] && SEEN.incrementAndGet() % VERIFY_EVERY == 0;
    }

    /** True while {@link #vanilla} runs vanilla's {@code compute} on this thread. */
    public static boolean runningVanilla() {
        return VERIFY_EVERY > 0 && VANILLA.get()[0];
    }

    /** Vanilla's {@code compute} (with every other mod's changes to it), bypassing the index. */
    public static double vanilla(DoubleSupplier compute) {
        boolean[] flag = VANILLA.get();
        flag[0] = true;
        try {
            return compute.getAsDouble();
        } finally {
            flag[0] = false;
        }
    }

    public static void verified(double expected, double actual, Object where) {
        VERIFIED.incrementAndGet();
        if (Double.doubleToRawLongBits(expected) != Double.doubleToRawLongBits(actual) && DIFFERING.incrementAndGet() <= 10) {
            Ga2Log.get("Verify").warn(
                "beardifier mismatch at {}: expected {}, got {}", where, expected, actual);
        }
    }

    public static boolean enabledFor(Object beardifier) {
        if (beardifier.getClass() != Beardifier.class || !DfcHooks.enabled()) {
            return false;
        }
        Boolean forced = override;
        if (forced != null ? !forced : !DfcConfig.beardifierIndex()) {
            return false;
        }
        return MixinTargets.beardifierAllowed() && !BeardExtras.present();
    }

    /**
     * True when the index may run with the steps other mods add after vanilla's sum ({@link BeardExtras}), decided per
     * beardifier on first use, once those mods have filled their lists.
     */
    public static boolean enabledWithExtras(Object beardifier) {
        if (beardifier.getClass() != Beardifier.class || !DfcHooks.enabled()) {
            return false;
        }
        Boolean forced = override;
        if (forced != null ? !forced : !DfcConfig.beardifierIndex()) {
            return false;
        }
        return MixinTargets.beardifierAllowed() && BeardExtras.present() && BeardExtras.ready();
    }
}
