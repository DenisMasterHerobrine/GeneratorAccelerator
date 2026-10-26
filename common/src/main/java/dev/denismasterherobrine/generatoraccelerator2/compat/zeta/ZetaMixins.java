package dev.denismasterherobrine.generatoraccelerator2.compat.zeta;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.compat.CodeShape;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;

/**
 * Zeta classes that GA2's cluster walk ({@link ZetaClusters}) works with. Contains no Minecraft types, because the mixin
 * plugin reads it.
 */
public final class ZetaMixins {
    private static final Logger LOGGER = Ga2Log.get("Compat");

    public static final String CLUSTER_BASED = "org.violetmoon.zeta.world.generator.multichunk.ClusterBasedGenerator";
    public static final String MULTI_CHUNK = "org.violetmoon.zeta.world.generator.multichunk.MultiChunkFeatureGenerator";
    public static final String SHAPE = "org.violetmoon.zeta.world.generator.multichunk.ClusterShape";
    public static final String PROVIDER = SHAPE + "$Provider";
    public static final String CONTEXT = CLUSTER_BASED + "$IGenerationContext";
    public static final String QUARK_STONE = "org.violetmoon.quark.content.world.gen.BigStoneClusterGenerator";
    public static final String QUARK_STONE_CONTEXT = QUARK_STONE + "$1";
    public static final String LAMBDA = "lambda$generateChunkPart$0";
    /** The walk's mixin and the two mixins that inspect what the walk relies on. Applied only with Quark loaded and {@link #codeKnown}. */
    public static final Set<String> MIXINS = Set.of("dev.denismasterherobrine.generatoraccelerator2.compat.zeta.mixin.ClusterBasedGeneratorMixin",
        "dev.denismasterherobrine.generatoraccelerator2.compat.zeta.mixin.ZetaClustersInspectMixin",
        "dev.denismasterherobrine.generatoraccelerator2.compat.quark.mixin.StoneClustersInspectMixin");

    /**
     * The methods relied on, with fingerprints ({@link CodeShape}) of the code the walk was written against. That code is
     * Zeta 1.1-40 and Quark 4.1-486 and -487. The walk does the work of Zeta's loop and its lambda instead. The walk does the
     * first test of {@code isInside} first. Quark's {@code canPlaceAt} and {@code canPlaceBlock} must stay free of side effects.
     */
    private static final List<Known> KNOWN = List.of(
        new Known(CLUSTER_BASED, "generateChunkPart", "772e6a115c81dc67"),
        new Known(CLUSTER_BASED, LAMBDA, "d2e939bffbe989c4"),
        new Known(MULTI_CHUNK, "forEachChunkBlock", "53482f73d89345f2"),
        new Known(SHAPE, "isInside", "8d1aae04faf17606"),
        new Known(QUARK_STONE, "canPlaceBlock", "d6d6cdf5bbf64b33"),
        new Known(QUARK_STONE_CONTEXT, "canPlaceAt", "69ad5720a8175c30"));

    private record Known(String owner, String method, String fingerprint) {}

    private static volatile Boolean known;

    private ZetaMixins() {}

    /**
     * True when every method the walk relies on has the code the walk was written against, and Quark's generator does not
     * override Zeta's loop. The classes are read as the mixin service reads them, before any mixin applies. Logs what differs.
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
                LOGGER.info("{}.{}: unknown code ({}) -> Zeta clusters use own loop",
                    k.owner(), k.method(), got);
                return false;
            }
        }
        ClassNode stone = nodes.get(QUARK_STONE);
        for (String own : List.of("generateChunkPart", "forEachChunkBlock")) {
            if (CodeShape.declares(stone, own)) {
                LOGGER.info("{} overrides {} -> Zeta clusters use own loop", QUARK_STONE, own);
                return false;
            }
        }
        return true;
    }
}
