package dev.denismasterherobrine.generatoraccelerator2.compat.spelunkery;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.compat.CodeShape;
import java.util.List;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;

/**
 * Spelunkery classes that GA2's fused cellular noise ({@link SpelunkeryCells}) works with. Contains no Minecraft types, because
 * the mixin plugin reads it.
 */
public final class SpelunkeryMixins {
    private static final Logger LOGGER = Ga2Log.get("Compat");

    public static final String MOD_ID = "spelunkery";
    public static final String NOISE = "com.ordana.spelunkery.worldgen.feature_configs.util.FastNoiseLite";
    public static final String STRIPES_MIXIN = "dev.denismasterherobrine.generatoraccelerator2.compat.spelunkery.mixin.BlockStripeFeatureMixin";

    /**
     * The methods whose work the fused noise does, with fingerprints ({@link CodeShape}) of the code the fused noise was written
     * against. That code is Spelunkery 0.4.4's FastNoiseLite. The methods are the 3D noise entry with its coordinate transforms, the
     * single-noise dispatch, the cellular search, the search's hash and rounding, and the min and max that the search folds its
     * distances with.
     */
    private static final List<String[]> KNOWN = List.of(
        new String[] { "GetNoise", "(DDD)F", "e855951230ef5de1" },
        new String[] { "GenNoiseSingle", "(IDDD)F", "c29484adb6964b6d" },
        new String[] { "SingleCellular", "(IDDD)F", "2c219c82529d932d" },
        new String[] { "Hash", "(IIII)I", "0a981efbea12c602" },
        new String[] { "FastRound", "(D)I", "611e2d14d80a25c0" },
        new String[] { "FastMin", "(FF)F", "2268dda849367e54" },
        new String[] { "FastMax", "(FF)F", "632d65ab5782a4f2" });

    private static volatile Boolean known;

    private SpelunkeryMixins() {}

    /** True when every method whose work the fused noise does has the code the fused noise was written against. Logs what differs. */
    public static boolean codeKnown() {
        Boolean k = known;
        if (k == null) {
            known = k = check();
        }
        return k;
    }

    private static boolean check() {
        ClassNode node = CodeShape.classNode(NOISE);
        for (String[] m : KNOWN) {
            String got = CodeShape.fingerprint(CodeShape.method(node, m[0], m[1]));
            if (!got.equals(m[2])) {
                LOGGER.info("{}.{}{}: unknown code ({}) -> fused cellular noise off, stripes use own noise",
                    NOISE, m[0], m[1], got);
                return false;
            }
        }
        return true;
    }
}
