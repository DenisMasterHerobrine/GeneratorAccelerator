package dev.denismasterherobrine.generatoraccelerator2.compat.moogs;

import dev.denismasterherobrine.generatoraccelerator2.compat.ProcessorCode;
import dev.denismasterherobrine.generatoraccelerator2.structure.TemplatePlacement;
import java.util.List;
import java.util.Map;

/**
 * Moog's Structure Lib processors the template clip takes ({@link TemplatePlacement}, {@link ProcessorCode}). Moog's pool
 * elements add the hanging entity anchor to every piece, so without it each piece of Moog's structures ran over its whole template.
 *
 * <p>The anchor and the armor stand processor only handle entities. The vault and trial spawner randomizers rewrite a block's NBT
 * from the block alone. The spawner randomizer and the loot swap draw from the settings' random at the block's position. The
 * pillar processor builds pillars under trigger blocks of the chunk being generated, and only returns a block elsewhere.
 * {@code flood_with_water} is left out: it schedules fluid ticks for water blocks outside that chunk too. Fingerprints are of
 * Moog's Structure Lib 3.4.2.
 */
public final class MoogsProcessors {
    private MoogsProcessors() {}

    public static final String MOD_ID = "moogs_structures";
    private static final String PROCESSORS = "com.finndog.moogs_structures.world.processors.";

    public static void register() {
        ProcessorCode.register(List.of(
            new ProcessorCode.Entry(PROCESSORS + "HangingEntityAnchorProcessor", TemplatePlacement.NO_OP, Map.of()),
            new ProcessorCode.Entry(PROCESSORS + "EquipArmorStandProcessor", TemplatePlacement.NO_OP, Map.of()),
            new ProcessorCode.Entry(PROCESSORS + "VaultRandomizingProcessor", TemplatePlacement.PLAIN, Map.of("processBlock", "2e9a3e7d9ee16e1f")),
            new ProcessorCode.Entry(PROCESSORS + "TrialSpawnerRandomizingProcessor", TemplatePlacement.PLAIN, Map.of("processBlock", "6ed5793556c0fbae")),
            new ProcessorCode.Entry(PROCESSORS + "SpawnerRandomizingProcessor", TemplatePlacement.DRAWS, Map.of("processBlock", "637a00c174f4c5f7",
                "buildSpawnerNbt", "6015c6bdd49e31ff", "pickWeightedRandom", "9d5f6a7838040167")),
            new ProcessorCode.Entry(PROCESSORS + "VanillaLootSwapProcessor", TemplatePlacement.DRAWS, Map.of("processBlock", "157504cf582ab599")),
            new ProcessorCode.Entry(PROCESSORS + "PillarProcessor", TemplatePlacement.CENTER_DRAWS, Map.of("processBlock", "a557bf306018b495",
                "getReturnBlock", "6f45ab3c19e8f9c7"))));
    }
}
