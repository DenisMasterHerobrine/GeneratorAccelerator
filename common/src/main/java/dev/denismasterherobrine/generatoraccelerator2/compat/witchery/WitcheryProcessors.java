package dev.denismasterherobrine.generatoraccelerator2.compat.witchery;

import dev.denismasterherobrine.generatoraccelerator2.compat.ProcessorCode;
import dev.denismasterherobrine.generatoraccelerator2.structure.TemplatePlacement;
import java.util.List;
import java.util.Map;

/**
 * Witchery: Forbidden Magic's ancient tablet processor, taken by the template clip ({@link TemplatePlacement}, {@link ProcessorCode}).
 * Its pieces sit in the deep dark. It turns a tablet to face the placement's rotation and drops one key of its NBT, from the block
 * alone. Fingerprint of Witchery: Forbidden Magic 0.5.22.
 */
public final class WitcheryProcessors {
    private WitcheryProcessors() {}

    public static final String MOD_ID = "witchery_forbidden_magic";

    public static void register() {
        ProcessorCode.register(List.of(
            new ProcessorCode.Entry("dev.sterner.witchery_forbidden_magic.AncientTabletProcessor", TemplatePlacement.PLAIN, Map.of("process", "9f69dce35e85ec54"))));
    }
}
