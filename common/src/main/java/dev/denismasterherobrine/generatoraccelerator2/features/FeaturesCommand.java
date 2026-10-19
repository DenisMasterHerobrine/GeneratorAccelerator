package dev.denismasterherobrine.generatoraccelerator2.features;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** {@code /ga2 features stats|report|mods}. */
public final class FeaturesCommand {
    private FeaturesCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("features")
            .then(Commands.literal("stats").executes(ctx -> stats(ctx.getSource())))
            .then(Commands.literal("report").executes(ctx -> report(ctx.getSource())))
            .then(Commands.literal("mods").executes(ctx -> mods(ctx.getSource())));
    }

    private static int stats(CommandSourceStack source) {
        send(source, "regions " + FeatureStats.REGIONS.sum() + ", placements through the pipeline " + FeatureStats.PLACEMENTS_FAST.sum()
            + " (other modifiers: " + FeatureStats.PLACEMENTS_GENERIC_FILTERS.sum() + " filters and " + FeatureStats.PLACEMENTS_GENERIC_REPEATS.sum()
            + " repeats without their streams, " + FeatureStats.PLACEMENTS_GENERIC_MODIFIERS.sum() + " through their own streams)");
        if (FeatureStats.DETAILED) {
            send(source, "chunk cache hits " + FeatureStats.CHUNK_CACHE_HITS.sum() + ", misses " + FeatureStats.CHUNK_CACHE_MISSES.sum()
                + "; fast writes " + FeatureStats.WRITES_FAST.sum() + ", delegated " + FeatureStats.WRITES_DELEGATED.sum()
                + "; biome memo hits " + FeatureStats.BIOME_MEMO_HITS.sum() + ", biome set hits " + FeatureStats.BIOME_SET_HITS.sum());
        } else {
            send(source, "per-block counters need -Dgeneratoraccelerator2.features.stats=true");
        }
        send(source, "region biome zooms " + FeatureStats.REGION_ZOOMS.sum() + (FeatureStats.DETAILED ? " (" + FeatureStats.REGION_BIOME_LOOKUPS.sum() + " lookups)" : "")
            + "; final heightmaps primed from the worldgen maps " + dev.denismasterherobrine.generatoraccelerator2.chunkio.HeightmapPriming.FAST.sum()
            + " chunks, kept as maintained " + dev.denismasterherobrine.generatoraccelerator2.chunkio.HeightmapPriming.KEPT.sum()
            + ", by vanilla " + dev.denismasterherobrine.generatoraccelerator2.chunkio.HeightmapPriming.VANILLA.sum());
        if (FeatureStats.SCULK_OTHERS_NEAR.sum() > 0) {
            send(source, "sculk patches left to vanilla (another mod's sculk block within reach): " + FeatureStats.SCULK_OTHERS_NEAR.sum());
        }
        Map<String, Long> fast = new TreeMap<>();
        for (Map.Entry<String, LongAdder> e : FeatureStats.fastByFeature().entrySet()) {
            fast.put(e.getKey(), e.getValue().sum());
        }
        Map<String, Long> fallback = new TreeMap<>();
        for (Map.Entry<String, LongAdder> e : FeatureStats.fallbackByFeature().entrySet()) {
            fallback.put(e.getKey(), e.getValue().sum());
        }
        send(source, "fast paths " + fast + (fallback.isEmpty() ? "" : ", fallbacks " + fallback));
        Map<String, Long> generic = new TreeMap<>();
        for (Map.Entry<String, LongAdder> e : FeatureStats.genericModifiers().entrySet()) {
            generic.put(e.getKey(), e.getValue().sum());
        }
        send(source, "generic placement modifiers " + generic);
        return 1;
    }

    private static int report(CommandSourceStack source) {
        for (String line : FeaturesCompat.report()) {
            send(source, line);
        }
        if (!FeatureTargets.allForeign().isEmpty()) {
            send(source, "foreign mixins on hooked methods: " + FeatureTargets.allForeign());
        }
        return 1;
    }

    /** Modded feature classes placed so far: placements, and whether they decorate in parallel or one at a time (and why). */
    private static int mods(CommandSourceStack source) {
        for (String line : dev.denismasterherobrine.generatoraccelerator2.features.modded.ModdedFeatures.report()) {
            send(source, line);
        }
        return 1;
    }

    private static void send(CommandSourceStack source, String line) {
        source.sendSuccess(() -> Component.literal(line), false);
    }
}
