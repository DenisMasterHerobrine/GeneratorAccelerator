package dev.denismasterherobrine.generatoraccelerator2.scheduling;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.denismasterherobrine.generatoraccelerator2.biome.BiomeAnswers;
import dev.denismasterherobrine.generatoraccelerator2.biome.ClimateSearch;
import dev.denismasterherobrine.generatoraccelerator2.biome.FlatClimateTree;
import dev.denismasterherobrine.generatoraccelerator2.biome.FusedLookup;
import dev.denismasterherobrine.generatoraccelerator2.biome.SearchTies;
import dev.denismasterherobrine.generatoraccelerator2.carver.CarverPaths;
import dev.denismasterherobrine.generatoraccelerator2.carver.CarverRunner;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.biolith.BiolithSearch;
import dev.denismasterherobrine.generatoraccelerator2.compat.blueprint.BlueprintClimate;
import dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched.LithostitchedInjectors;
import dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched.LithostitchedRegions;
import dev.denismasterherobrine.generatoraccelerator2.compat.spelunkery.SpelunkeryCells;
import dev.denismasterherobrine.generatoraccelerator2.compat.supplementaries.Barnacles;
import dev.denismasterherobrine.generatoraccelerator2.compat.zeta.ZetaClusters;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.SurfaceLevels;
import dev.denismasterherobrine.generatoraccelerator2.features.FeatureStats;
import dev.denismasterherobrine.generatoraccelerator2.features.placement.EveryLayer;
import dev.denismasterherobrine.generatoraccelerator2.features.placement.PatchRepeats;
import dev.denismasterherobrine.generatoraccelerator2.features.region.RegionReads;
import dev.denismasterherobrine.generatoraccelerator2.light.FastLight;
import dev.denismasterherobrine.generatoraccelerator2.mixin.scheduling.ChunkMapHoldersAccessor;
import dev.denismasterherobrine.generatoraccelerator2.state.PoiStates;
import dev.denismasterherobrine.generatoraccelerator2.storage.FastPack;
import dev.denismasterherobrine.generatoraccelerator2.storage.LoadPrep;
import dev.denismasterherobrine.generatoraccelerator2.storage.Precompress;
import dev.denismasterherobrine.generatoraccelerator2.storage.RegionScan;
import dev.denismasterherobrine.generatoraccelerator2.storage.RegionSync;
import dev.denismasterherobrine.generatoraccelerator2.storage.SavePrep;
import dev.denismasterherobrine.generatoraccelerator2.structure.HeightProbes;
import dev.denismasterherobrine.generatoraccelerator2.structure.TemplatePlacement;
import dev.denismasterherobrine.generatoraccelerator2.surface.NoiseBiomeMemo;
import dev.denismasterherobrine.generatoraccelerator2.surface.SurfaceBuilder;
import dev.denismasterherobrine.generatoraccelerator2.surface.SurfaceHooks;
import dev.denismasterherobrine.generatoraccelerator2.surface.compile.SurfaceProgram;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** {@code /ga2 steps stats}: how many surface and carver steps ran on the worker pool or inline. */
public final class StepsCommand {
    private StepsCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("steps")
            .then(Commands.literal("stats").executes(ctx -> stats(ctx.getSource())))
            .then(Commands.literal("holders").executes(ctx -> holders(ctx.getSource())));
    }

    /**
     * The dimension's chunk holders: loaded ones by their latest status, and those waiting to unload by why they still wait
     * (a generation task's claim, a pending save dependency). A holder stays loaded, with its chunk, as long as either holds.
     */
    private static int holders(CommandSourceStack source) {
        ChunkMap map = source.getLevel().getChunkSource().chunkMap;
        var acc = (ChunkMapHoldersAccessor) map;
        Map<String, Integer> byStatus = new TreeMap<>();
        int loaded = 0, claimed = 0, saving = 0;
        for (ChunkHolder holder : acc.ga2$updatingChunkMap().values()) {
            loaded++;
            ChunkStatus status = holder.getLatestStatus();
            byStatus.merge(status == null ? "none" : status.getName(), 1, Integer::sum);
            if (holder.getGenerationRefCount() > 0) {
                claimed++;
            }
            if (!holder.getSaveSyncFuture().isDone()) {
                saving++;
            }
        }
        int pending = 0, pendingClaimed = 0, pendingSaving = 0;
        Map<String, Integer> pendingByStatus = new TreeMap<>();
        for (ChunkHolder holder : acc.ga2$pendingUnloads().values()) {
            pending++;
            ChunkStatus status = holder.getLatestStatus();
            pendingByStatus.merge(status == null ? "none" : status.getName(), 1, Integer::sum);
            if (holder.getGenerationRefCount() > 0) {
                pendingClaimed++;
            }
            if (!holder.getSaveSyncFuture().isDone()) {
                pendingSaving++;
            }
        }
        send(source, "holders loaded: " + loaded + " " + byStatus + "; claimed by generation tasks " + claimed + ", save dependency pending " + saving);
        send(source, "holders waiting to unload: " + pending + " " + pendingByStatus + "; claimed by generation tasks " + pendingClaimed
            + ", save dependency pending " + pendingSaving);
        return 1;
    }

    private static int stats(CommandSourceStack source) {
        send(source, "surface: parallel " + ParallelSteps.SURFACE_ASYNC.sum() + " (" + ParallelSteps.SURFACE_DEFERRED.sum()
            + " started once the GPU's surface was in), inline " + ParallelSteps.SURFACE_INLINE.sum() + " (config " + SchedulingConfig.parallelSurface() + ")");
        if (!HookTargets.handled(HookTargets.SURFACE_STEP_BODY).isEmpty() || !HookTargets.foreign(HookTargets.SURFACE_STEP_BODY).isEmpty()) {
            send(source, "surface step run by GA2 with other mods' code on the pool: " + ParallelSteps.SURFACE_TAKEN.sum() + " ("
                + (HookTargets.allowed(HookTargets.SURFACE_STEP_BODY) ? "on: " + HookTargets.handledMixins(HookTargets.SURFACE_STEP_BODY)
                    : "off: " + HookTargets.foreign(HookTargets.SURFACE_STEP_BODY) + " also change the step")
                + "); their biome lookups " + NoiseBiomeMemo.NoiseBiomeStats.LOOKUPS.sum()
                + ", biome source asked " + NoiseBiomeMemo.NoiseBiomeStats.ASKED.sum());
        }
        send(source, "carvers: parallel " + ParallelSteps.CARVERS_ASYNC.sum() + ", inline " + ParallelSteps.CARVERS_INLINE.sum()
            + " (config " + SchedulingConfig.parallelCarvers() + ")");
        send(source, "structure starts: parallel " + ParallelSteps.STARTS_ASYNC.sum() + " (" + ParallelSteps.STARTS_AFTER_RINGS.sum()
            + " queued behind the ring positions), inline " + ParallelSteps.STARTS_INLINE.sum() + " (config " + SchedulingConfig.parallelStructureStarts() + ")");
        int[] locks = AreaLocks.totals();
        send(source, "features: parallel " + ParallelSteps.FEATURES_ASYNC.sum() + " (" + ParallelSteps.FEATURES_WAITED.sum() + " waited for a neighbour), inline "
            + ParallelSteps.FEATURES_INLINE.sum() + " (config " + SchedulingConfig.parallelFeatures() + ", guard radius " + SchedulingConfig.featuresGuardRadius()
            + "); light initializations that waited: " + ParallelSteps.LIGHT_WAITED.sum() + "; area locks now: " + locks[0] + " held, " + locks[1] + " waiting");
        send(source, "surface builder: GA2 " + SurfaceHooks.CHUNKS_FAST.sum() + " (with other mods' rules "
            + SurfaceHooks.CHUNKS_OPAQUE.sum() + ", with their column code "
            + SurfaceHooks.CHUNKS_HOOKED.sum() + "), vanilla "
            + SurfaceHooks.CHUNKS_VANILLA.sum() + "; deep columns "
            + SurfaceHooks.COLUMNS_DEEP.sum() + ", bulk sections "
            + SurfaceHooks.SECTIONS_BULK.sum());
        send(source, "carver runner: GA2 " + CarverRunner.CHUNKS_FAST.sum() + ", vanilla "
            + CarverRunner.CHUNKS_VANILLA.sum() + "; starts replayed "
            + CarverRunner.CARVES_REPLAYED.sum() + ", other carvers "
            + CarverRunner.CARVES_FOREIGN.sum() + "; paths simulated "
            + CarverPaths.SIMULATED.sum() + ", reused " + CarverPaths.HITS.sum()
            + "; top materials from rules built once per chunk " + CarverRunner.topReused()
            + ", built per call (vanilla's) " + CarverRunner.topVanilla());
        send(source, "POI bookkeeping left out for writes between states without a POI type: " + (FeatureStats.DETAILED
                ? String.valueOf(PoiStates.SKIPPED.sum()) : "counted with -Dgeneratoraccelerator2.features.stats=true")
            + (HookTargets.allowed(HookTargets.STATE_POI_SKIP) ? "" : " (off: another mod changes it)"));
        if (SpelunkeryCells.searched() > 0) {
            send(source, "Spelunkery stripes' cellular noise: points searched " + SpelunkeryCells.searched()
                + ", answers taken from the other noise's search " + SpelunkeryCells.shared());
        }
        if (ZetaClusters.PARTS.sum() > 0) {
            send(source, "Zeta cluster parts walked by GA2 " + ZetaClusters.PARTS.sum() + " (cluster out of reach of the chunk "
                + ZetaClusters.OUT_OF_REACH.sum() + "), blocks asked whether a stone may go there "
                + ZetaClusters.ASKED.sum());
        }
        if (Barnacles.PLACEMENTS.sum() > 0) {
            send(source, "Supplementaries' barnacles searched once per direction: " + Barnacles.PLACEMENTS.sum()
                + " placements, rounds left out " + Barnacles.ROUNDS_SKIPPED.sum());
        }
        send(source, "random patch tries whose inner feature only reads: " + PatchRepeats.TRIED.sum()
            + ", answered from an earlier try at the same position " + PatchRepeats.SKIPPED.sum()
            + (HookTargets.allowed(HookTargets.PATCH_REPEATS) ? "" : " (off: another mod changes it)"));
        send(source, "structure references: parallel " + ParallelSteps.REFERENCES_ASYNC.sum() + ", inline " + ParallelSteps.REFERENCES_INLINE.sum()
            + "; light initializations on the pool " + ParallelSteps.LIGHT_INIT_ASYNC.sum());
        send(source, "light: fast engine " + (FastLight.on ? "on" : "off")
            + "; saving: sections packed " + FastPack.PACKED.sum()
            + ", chunks compressed on the pool " + Precompress.USED.sum()
            + ", by the IO thread " + Precompress.INLINE.sum()
            + " (pool tasks taken back " + Precompress.SKIPPED.sum()
            + ", waited for " + Precompress.WAITED.sum() + ")"
            + ", region old-chunk scans " + RegionScan.SCANS.sum());
        send(source, SavePrep.stats());
        send(source, RegionSync.stats());
        send(source, TemplatePlacement.stats());
        send(source, LoadPrep.stats());
        long aliased = BlueprintClimate.ALIASED.sum();
        if (aliased > 0) {
            long verified = BlueprintClimate.VERIFIED.sum();
            send(source, "Blueprint's slice samplers served by the compiled climate program: " + aliased
                + (verified > 0 ? "; target points verified " + verified + ", differing " + BlueprintClimate.DIFFERING.sum() : ""));
        }
        long probes = HeightProbes.ASKED.sum();
        if (probes > 0) {
            send(source, "structure height probes: " + probes + " asked, " + HeightProbes.REMEMBERED.sum()
                + " answered from earlier probes of the same column; surface levels by the compiled walk "
                + SurfaceLevels.COLUMNS.sum());
        }
        long outsideAsked = BiomeAnswers.ASKED.sum();
        if (outsideAsked > 0) {
            send(source, "biomes asked outside a region's chunks: " + outsideAsked + ", answered from earlier answers "
                + (outsideAsked - BiomeAnswers.LOOKED_UP.sum()) + ", not kept " + BiomeAnswers.UNSURE.sum()
                + " (searches with another leaf as near: GA2's " + SearchTies.REASONS[0].sum()
                + ", Biolith's first answer " + SearchTies.REASONS[1].sum()
                + ", Biolith's second " + SearchTies.REASONS[2].sum()
                + "; searches GA2 does not see " + SearchTies.REASONS[3].sum() + ")");
        }
        long readRegions = RegionReads.REGIONS.sum();
        long layerSearches = EveryLayer.CALLS.sum();
        if (readRegions + layerSearches > 0) {
            long readsVerified = RegionReads.VERIFIED.sum();
            long layersVerified = EveryLayer.VERIFIED.sum();
            send(source, "regions reading in place: " + readRegions + "; layer placements transcribed: " + layerSearches
                + (readsVerified > 0 ? "; reads verified " + readsVerified + ", differing " + RegionReads.DIFFERING.sum() : "")
                + (layersVerified > 0 ? "; layer scans verified " + layersVerified + ", differing " + EveryLayer.DIFFERING.sum() : ""));
        }
        long biomePrograms = SurfaceProgram.BIOME_PROGRAMS.sum();
        if (biomePrograms > 0) {
            send(source, "surface rules compiled per biome: " + biomePrograms + " programs (shared by biomes with the same folded rules "
                + SurfaceProgram.BIOME_PROGRAMS_SHARED.sum() + " times), picked "
                + SurfaceProgram.BIOME_PICKS.sum() + " times; rules asked as a new sequence of known rules "
                + SurfaceBuilder.SHAPE_HITS.sum() + " times");
        }
        long columnAnswers = FlatClimateTree.GROUP_ANSWERED.sum();
        if (columnAnswers > 0 || ClimateSearch.VERIFIED.get() > 0) {
            long fills = FlatClimateTree.GROUP_FILLS.sum();
            send(source, String.format(Locale.ROOT, "climate searches answered per quart column: %,d (walked for shared distances %,d, trees without groups %,d, out of range %,d; columns filled %,d, %.1f nodes each; turned off for a while %,d times)",
                columnAnswers, FlatClimateTree.GROUP_WALKED.sum(),
                FlatClimateTree.WALK_NO_GROUPS.sum(), FlatClimateTree.WALK_OUT_OF_RANGE.sum(), fills,
                fills == 0 ? 0.0 : (double) FlatClimateTree.FILL_NODES.sum() / fills, FlatClimateTree.COLUMNS_OFF.sum())
                + (ClimateSearch.VERIFIED.get() > 0 ? "; verified against the walk "
                    + ClimateSearch.VERIFIED.get() + ", differing "
                    + ClimateSearch.DIFFERING.get() : ""));
        }
        if (FusedLookup.VERIFIED.get() > 0) {
            send(source, "verify fused biome lookups: " + FusedLookup.VERIFIED.get()
                + " compared with the sample and search, differing " + FusedLookup.DIFFERING.get());
        }
        // the trees with the most column fills: nodes, depth groups, fills, searches answered from them
        List<FlatClimateTree> trees = FlatClimateTree.all();
        trees.sort(Comparator.comparingInt((FlatClimateTree t) -> t.statFills).reversed());
        for (int i = 0; i < Math.min(8, trees.size()) && trees.get(i).statFills > 0; i++) {
            var t = trees.get(i);
            send(source, String.format(Locale.ROOT, "  climate tree of %,d nodes, %d depth groups: %,d columns filled, %,d searches answered from them", t.nodes(), t.groupCount(),
                t.statFills, t.statAnswers));
        }
        long biolithFlat = BiolithSearch.FLAT.sum();
        long biolithOwn = BiolithSearch.BIOLITH.sum();
        if (biolithFlat + biolithOwn > 0) {
            long verified = BiolithSearch.VERIFIED.get();
            send(source, "Biolith's climate searches: through GA2's flat tree " + biolithFlat + ", its own " + biolithOwn
                + (verified > 0 ? "; verified " + verified + ", differing " + BiolithSearch.DIFFERING.get() : ""));
        }
        long regionComputed = LithostitchedRegions.COMPUTED.sum();
        long regionRemembered = LithostitchedRegions.REMEMBERED.sum();
        if (regionComputed + regionRemembered > 0) {
            send(source, "Lithostitched's region function (column-only): computed " + regionComputed + ", remembered " + regionRemembered);
        }
        long walks = LithostitchedInjectors.filtered();
        if (walks > 0) {
            long checked = LithostitchedInjectors.CHECKED.sum();
            send(source, "Lithostitched's partial replacements: " + walks + " walks over the biome's subset, "
                + LithostitchedInjectors.skipped() + " replacements skipped"
                + (checked > 0 ? "; noted biome checked " + checked + ", wrong " + LithostitchedInjectors.MISNOTED.sum() : ""));
        }
        if (!HookTargets.unchanged(HookTargets.SURFACE_ENTRY)) {
            send(source, "buildSurface(WorldGenRegion, ...) is changed by " + HookTargets.foreign(HookTargets.SURFACE_ENTRY) + "; it is called as is");
        }
        return 1;
    }

    private static void send(CommandSourceStack source, String line) {
        source.sendSuccess(() -> Component.literal(line), false);
    }
}
