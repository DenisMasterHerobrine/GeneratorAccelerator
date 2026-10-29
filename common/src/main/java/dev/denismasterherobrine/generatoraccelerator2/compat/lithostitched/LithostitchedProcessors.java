package dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched;

import com.google.common.collect.MapMaker;
import com.mojang.datafixers.util.Either;
import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.compat.CodeShape;
import dev.denismasterherobrine.generatoraccelerator2.compat.Reflect;
import dev.denismasterherobrine.generatoraccelerator2.structure.TemplatePlacement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList;

/**
 * Lithostitched's structure processors that work block by block, for the clip of template placements ({@link TemplatePlacement}).
 * Villages of ChoiceTheorem's Overhauled Villages use them on most pieces, and every such placement ran over its whole template.
 *
 * <p>Each random of these processors comes from the world seed and a block, piece or template position ({@code RandomSettings}),
 * never from the settings' shared random, so the blocks can be processed in any number and order. {@code block_swap} and
 * {@code set_block} work on the block alone. {@code condition}, {@code reference} and {@code apply_random} only run the
 * {@code processBlock} of the processors they hold, which must be known themselves (checked recursively). So does the unbound
 * reference Lithostitched adds to vanilla's non-jigsaw pieces (ruined portals, igloos, shipwrecks and others), for the list of its
 * name in the level's registry. A name without a list, or with an empty one as Lithostitched ships them, leaves the block as it
 * is. A condition is known when it is built of {@code all_of}, {@code any_of}, {@code not}, {@code true},
 * {@code random_chance}, {@code matching_blocks} and {@code position} with a vanilla position test. {@code matching_biomes} is
 * left out: its biome lookups go through the climate search's per-thread last result, which a clipped placement would leave
 * elsewhere. The code relied on is fingerprinted ({@link CodeShape}, Lithostitched 1.8.1).
 */
public final class LithostitchedProcessors {
    private LithostitchedProcessors() {}

    private static final String PROCESSOR = "dev.worldgen.lithostitched.impl.worldgen.processor.";
    private static final String BLOCK_SWAP = PROCESSOR + "BlockSwapStructureProcessor";
    private static final String SET_BLOCK = PROCESSOR + "SetBlockProcessor";
    private static final String CONDITION = PROCESSOR + "ConditionProcessor";
    private static final String REFERENCE = PROCESSOR + "ReferenceStructureProcessor";
    private static final String APPLY_RANDOM = PROCESSOR + "ApplyRandomStructureProcessor";
    private static final String UNBOUND = PROCESSOR + "UnboundReferenceProcessor";
    private static final String RANDOM_SETTINGS = "dev.worldgen.lithostitched.api.worldgen.processor.RandomSettings";
    private static final String CONDITIONS = "dev.worldgen.lithostitched.worldgen.processor.condition.";
    private static final String WEIGHTED_SET = "dev.worldgen.lithostitched.api.util.WeightedHolderSet";

    private record Known(String owner, String method, String fingerprint) {}

    private static final List<Known> KNOWN = List.of(
        new Known(BLOCK_SWAP, "processBlock", "0c4d532a07d40539"),
        new Known(SET_BLOCK, "processBlock", "78b6bba09f91702e"),
        new Known(SET_BLOCK, "withState", "8673b2cd93f86c94"),
        new Known(CONDITION, "processBlock", "8d4b39a8ccbf68e2"),
        new Known(APPLY_RANDOM, "processBlock", "73916d2d4ed64dae"),
        new Known(REFERENCE, "processBlock", "b180ab901292f319"),
        new Known(UNBOUND, "processBlock", "00713b662c1aeccd"),
        new Known("dev.worldgen.lithostitched.Lithostitched", "registry", "d2e1b1e8587ec439"),
        new Known(RANDOM_SETTINGS, "create", "0737621aa79b6dc7"),
        new Known(CONDITIONS + "AllOf", "test", "776a5e738a96f079"),
        new Known(CONDITIONS + "AnyOf", "test", "f48f263e69d1021a"),
        new Known(CONDITIONS + "Not", "test", "1bfa1444c1d712df"),
        new Known(CONDITIONS + "RandomChance", "test", "c25f80c73046af27"),
        new Known(CONDITIONS + "True", "test", "750102bdd7ce163c"),
        new Known(CONDITIONS + "MatchingBlocks", "test", "9eb290b781fc5f4b"),
        new Known(CONDITIONS + "Position", "test", "cbe2bd1f46bb366b"));

    /** Verdicts by processor; {@link #UNKNOWN} for one that cannot be clipped. Processors are immutable, so a verdict holds. */
    private static final ConcurrentMap<StructureProcessor, Object> VERDICTS = new MapMaker().weakKeys().makeMap();
    private static final Object UNKNOWN = new Object();
    /** Verdicts by the processor list an unbound reference names, which belongs to one registry load. */
    private static final ConcurrentMap<StructureProcessorList, Object> LIST_VERDICTS = new MapMaker().weakKeys().makeMap();

    public static void register() {
        for (Known k : KNOWN) {
            String got = CodeShape.fingerprint(CodeShape.method(CodeShape.classNode(k.owner()), k.method(), null));
            if (!got.equals(k.fingerprint())) {
                Ga2Log.get("Compat").info("{}.{}: unknown code ({}) -> Lithostitched's processors keep their placements whole", k.owner(), k.method(), got);
                return;
            }
        }
        TemplatePlacement.addClassifier(LithostitchedProcessors::classify);
    }

    @Nullable
    private static TemplatePlacement.Kind classify(StructureProcessor processor, RegistryAccess registries) {
        String name = processor.getClass().getName();
        if (!name.startsWith(PROCESSOR)) {
            return null;
        }
        if (name.equals(UNBOUND)) {
            // a new one for every placement. Its verdict follows the registry's list.
            return unbound(processor, registries);
        }
        Object v = VERDICTS.get(processor);
        if (v == null) {
            TemplatePlacement.Kind kind = verdict(processor, registries);
            v = kind == null ? UNKNOWN : kind;
            VERDICTS.put(processor, v);
        }
        return v == UNKNOWN ? null : (TemplatePlacement.Kind) v;
    }

    @Nullable
    private static TemplatePlacement.Kind verdict(StructureProcessor processor, RegistryAccess registries) {
        String name = processor.getClass().getName();
        switch (name) {
            case BLOCK_SWAP, SET_BLOCK -> {
                return TemplatePlacement.PLAIN;
            }
            case CONDITION -> {
                if (!knownCondition(Reflect.field(processor, CONDITION, "condition"))) {
                    return null;
                }
                TemplatePlacement.Kind then = all(Reflect.field(processor, CONDITION, "thenRun"), registries);
                TemplatePlacement.Kind otherwise = all(Reflect.field(processor, CONDITION, "elseRun"), registries);
                return then == null || otherwise == null ? null : then.and(otherwise);
            }
            case REFERENCE -> {
                return lists(Reflect.field(processor, REFERENCE, "processorLists"), registries);
            }
            case APPLY_RANDOM -> {
                Object weighted = Reflect.field(processor, APPLY_RANDOM, "processorLists");
                Object set = weighted == null ? null : Reflect.field(weighted, WEIGHTED_SET, "set");
                if (!(set instanceof Either<?, ?> either)) {
                    return null;
                }
                Optional<?> left = either.left();
                if (left.isPresent()) {
                    return lists(left.get(), registries);
                }
                List<Object> holders = new ArrayList<>();
                try {
                    Object list = either.right().orElseThrow();
                    for (Object w : (List<?>) list.getClass().getMethod("unwrap").invoke(list)) {
                        holders.add(w.getClass().getMethod("value").invoke(w));
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return null;
                }
                return lists(holders, registries);
            }
            default -> {
                return null;
            }
        }
    }

    /** The kind of the list the unbound reference names, or null when it is unknown. */
    @Nullable
    private static TemplatePlacement.Kind unbound(StructureProcessor processor, RegistryAccess registries) {
        if (!(Reflect.field(processor, UNBOUND, "name") instanceof ResourceKey<?> key)) {
            return null;
        }
        Optional<StructureProcessorList> list = registries.lookupOrThrow(Registries.PROCESSOR_LIST).getOptional(key.identifier());
        if (list.isEmpty() || list.get().list().isEmpty()) {
            return TemplatePlacement.NO_OP;
        }
        Object v = LIST_VERDICTS.get(list.get());
        if (v == null) {
            TemplatePlacement.Kind kind = all(list.get().list(), registries);
            v = kind == null ? UNKNOWN : kind;
            LIST_VERDICTS.put(list.get(), v);
        }
        return v == UNKNOWN ? null : (TemplatePlacement.Kind) v;
    }

    /** The kinds of the processor lists of {@code holders} (a holder set or a list of holders) together, or null if one is unknown. */
    @Nullable
    private static TemplatePlacement.Kind lists(@Nullable Object holders, RegistryAccess registries) {
        if (!(holders instanceof Iterable<?> each)) {
            return null;
        }
        TemplatePlacement.Kind kind = TemplatePlacement.PLAIN;
        for (Object h : each) {
            if (!(h instanceof Holder<?> holder) || !(holder.value() instanceof StructureProcessorList list)) {
                return null;
            }
            TemplatePlacement.Kind k = all(list.list(), registries);
            if (k == null) {
                return null;
            }
            kind = kind.and(k);
        }
        return kind;
    }

    /** The kinds of {@code processors} together, or null if one is unknown. */
    @Nullable
    private static TemplatePlacement.Kind all(@Nullable Object processors, RegistryAccess registries) {
        if (!(processors instanceof List<?> list)) {
            return null;
        }
        TemplatePlacement.Kind kind = TemplatePlacement.PLAIN;
        for (Object p : list) {
            TemplatePlacement.Kind k = p instanceof StructureProcessor sp ? TemplatePlacement.kind(sp, registries) : null;
            if (k == null) {
                return null;
            }
            kind = kind.and(k);
        }
        return kind;
    }

    private static boolean knownCondition(@Nullable Object condition) {
        if (condition == null) {
            return false;
        }
        String name = condition.getClass().getName();
        if (!name.startsWith(CONDITIONS)) {
            return false;
        }
        switch (name.substring(CONDITIONS.length())) {
            case "AllOf", "AnyOf" -> {
                if (!(Reflect.field(condition, name, "conditions") instanceof List<?> parts)) {
                    return false;
                }
                for (Object part : parts) {
                    if (!knownCondition(part)) {
                        return false;
                    }
                }
                return true;
            }
            case "Not" -> {
                return knownCondition(Reflect.field(condition, name, "condition"));
            }
            case "True", "RandomChance", "MatchingBlocks" -> {
                return true;
            }
            case "Position" -> {
                // vanilla's position tests read only the positions and the block's own random
                Object predicate = Reflect.field(condition, name, "predicate");
                return predicate != null && predicate.getClass().getName().startsWith("net.minecraft.");
            }
            default -> {
                return false;
            }
        }
    }
}
