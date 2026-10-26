package dev.denismasterherobrine.generatoraccelerator2.compat.supplementaries;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.compat.CodeShape;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;

/**
 * Supplementaries classes that GA2's barnacle search ({@link Barnacles}) works with. Contains no Minecraft types, because the
 * mixin plugin reads it.
 */
public final class SupplementariesMixins {
    private static final Logger LOGGER = Ga2Log.get("Compat");

    public static final String MOD_ID = "supplementaries";
    public static final String FEATURE = "net.mehvahdjukaar.supplementaries.common.worldgen.BarnaclesMultifaceGrowthFeature";
    public static final String CONFIG = FEATURE + "$Config";
    public static final String BLOCK = "net.mehvahdjukaar.supplementaries.common.block.blocks.BarnaclesBlock";
    /** The search's mixin and the mixin that inspects the block's placement test. Applied only with {@link #codeKnown}. */
    public static final Set<String> MIXINS = Set.of(
        "dev.denismasterherobrine.generatoraccelerator2.compat.supplementaries.mixin.BarnaclesFeatureMixin",
        "dev.denismasterherobrine.generatoraccelerator2.compat.supplementaries.mixin.BarnaclesBlockInspectMixin");

    /**
     * The methods relied on, with fingerprints ({@link CodeShape}) of the code the search was written against (Supplementaries
     * 1.21.1-3.9.6). The search does {@code place}'s work. {@code placeGrowthIfPossible} must write and draw nothing when it
     * returns false: it reads blocks and asks the block's placement test, which reads blocks too.
     */
    private static final List<Known> KNOWN = List.of(
        new Known(FEATURE, "place", "a295f0a47db0a883"),
        new Known(FEATURE, "placeGrowthIfPossible", "2c01a8e528c3370b"),
        new Known(FEATURE, "cantBePlacedOn", "342450ddaa267c4b"),
        new Known(FEATURE, "isAirOrWater", "fd14d4f4126f2dfd"),
        new Known(BLOCK, "isValidStateForPlacement", "1f9647dca7958509"),
        new Known(BLOCK, "canBeAttachTo", "1ca4c52660a047d1"));

    private record Known(String owner, String method, String fingerprint) {}

    private static volatile Boolean known;

    private SupplementariesMixins() {}

    /**
     * True when every method the search relies on has the code it was written against. The classes are read as the mixin service
     * reads them, before any mixin applies. Logs what differs.
     */
    public static boolean codeKnown() {
        Boolean k = known;
        if (k == null) {
            known = k = check();
        }
        return k;
    }

    private static boolean check() {
        Map<String, ClassNode> nodes = new HashMap<>();
        for (Known k : KNOWN) {
            ClassNode node = nodes.computeIfAbsent(k.owner(), CodeShape::classNode);
            String got = CodeShape.fingerprint(CodeShape.method(node, k.method(), null));
            if (!got.equals(k.fingerprint())) {
                LOGGER.info("{}.{}: unknown code ({}) -> barnacles use own search", k.owner(), k.method(), got);
                return false;
            }
        }
        return true;
    }
}
