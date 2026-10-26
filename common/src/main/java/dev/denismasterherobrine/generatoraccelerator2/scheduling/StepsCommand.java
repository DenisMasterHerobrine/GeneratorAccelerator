package dev.denismasterherobrine.generatoraccelerator2.scheduling;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

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
        net.minecraft.server.level.ChunkMap map = source.getLevel().getChunkSource().chunkMap;
        var acc = (dev.denismasterherobrine.generatoraccelerator2.mixin.scheduling.ChunkMapHoldersAccessor) map;
        java.util.Map<String, Integer> byStatus = new java.util.TreeMap<>();
        int loaded = 0, claimed = 0, saving = 0;
        for (net.minecraft.server.level.ChunkHolder holder : acc.ga2$updatingChunkMap().values()) {
            loaded++;
            net.minecraft.world.level.chunk.status.ChunkStatus status = holder.getLatestStatus();
            byStatus.merge(status == null ? "none" : status.getName(), 1, Integer::sum);
            if (holder.getGenerationRefCount() > 0) {
                claimed++;
            }
            if (!holder.getSaveSyncFuture().isDone()) {
                saving++;
            }
        }
        int pending = 0, pendingClaimed = 0, pendingSaving = 0;
        java.util.Map<String, Integer> pendingByStatus = new java.util.TreeMap<>();
        for (net.minecraft.server.level.ChunkHolder holder : acc.ga2$pendingUnloads().values()) {
            pending++;
            net.minecraft.world.level.chunk.status.ChunkStatus status = holder.getLatestStatus();
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
                + "); their biome lookups " + dev.denismasterherobrine.generatoraccelerator2.surface.NoiseBiomeMemo.NoiseBiomeStats.LOOKUPS.sum()
                + ", biome source asked " + dev.denismasterherobrine.generatoraccelerator2.surface.NoiseBiomeMemo.NoiseBiomeStats.ASKED.sum());
        }
        send(source, "carvers: parallel " + ParallelSteps.CARVERS_ASYNC.sum() + ", inline " + ParallelSteps.CARVERS_INLINE.sum()
            + " (config " + SchedulingConfig.parallelCarvers() + ")");
        send(source, "structure starts: parallel " + ParallelSteps.STARTS_ASYNC.sum() + " (" + ParallelSteps.STARTS_AFTER_RINGS.sum()
            + " queued behind the ring positions), inline " + ParallelSteps.STARTS_INLINE.sum() + " (config " + SchedulingConfig.parallelStructureStarts() + ")");
        int[] locks = AreaLocks.totals();
        send(source, "features: parallel " + ParallelSteps.FEATURES_ASYNC.sum() + " (" + ParallelSteps.FEATURES_WAITED.sum() + " waited for a neighbour), inline "
            + ParallelSteps.FEATURES_INLINE.sum() + " (config " + SchedulingConfig.parallelFeatures() + ", guard radius " + SchedulingConfig.featuresGuardRadius()
            + "); light initializations that waited: " + ParallelSteps.LIGHT_WAITED.sum() + "; area locks now: " + locks[0] + " held, " + locks[1] + " waiting");
        send(source, "surface builder: GA2 " + dev.denismasterherobrine.generatoraccelerator2.surface.SurfaceHooks.CHUNKS_FAST.sum() + " (with other mods' rules "
            + dev.denismasterherobrine.generatoraccelerator2.surface.SurfaceHooks.CHUNKS_OPAQUE.sum() + ", with their column code "
            + dev.denismasterherobrine.generatoraccelerator2.surface.SurfaceHooks.CHUNKS_HOOKED.sum() + "), vanilla "
            + dev.denismasterherobrine.generatoraccelerator2.surface.SurfaceHooks.CHUNKS_VANILLA.sum() + "; deep columns "
            + dev.denismasterherobrine.generatoraccelerator2.surface.SurfaceHooks.COLUMNS_DEEP.sum() + ", bulk sections "
            + dev.denismasterherobrine.generatoraccelerator2.surface.SurfaceHooks.SECTIONS_BULK.sum());
        send(source, "carver runner: GA2 " + dev.denismasterherobrine.generatoraccelerator2.carver.CarverRunner.CHUNKS_FAST.sum() + ", vanilla "
            + dev.denismasterherobrine.generatoraccelerator2.carver.CarverRunner.CHUNKS_VANILLA.sum() + "; starts replayed "
            + dev.denismasterherobrine.generatoraccelerator2.carver.CarverRunner.CARVES_REPLAYED.sum() + ", other carvers "
            + dev.denismasterherobrine.generatoraccelerator2.carver.CarverRunner.CARVES_FOREIGN.sum() + "; paths simulated "
            + dev.denismasterherobrine.generatoraccelerator2.carver.CarverPaths.SIMULATED.sum() + ", reused " + dev.denismasterherobrine.generatoraccelerator2.carver.CarverPaths.HITS.sum()
            + "; top materials from rules built once per chunk " + dev.denismasterherobrine.generatoraccelerator2.carver.CarverRunner.topReused()
            + ", built per call (vanilla's) " + dev.denismasterherobrine.generatoraccelerator2.carver.CarverRunner.topVanilla());
        send(source, "POI bookkeeping left out for writes between states without a POI type: " + (dev.denismasterherobrine.generatoraccelerator2.features.FeatureStats.DETAILED
                ? String.valueOf(dev.denismasterherobrine.generatoraccelerator2.state.PoiStates.SKIPPED.sum()) : "counted with -Dgeneratoraccelerator2.features.stats=true")
            + (dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets.allowed(dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets.STATE_POI_SKIP) ? "" : " (off: another mod changes it)"));
        if (dev.denismasterherobrine.generatoraccelerator2.compat.spelunkery.SpelunkeryCells.searched() > 0) {
            send(source, "Spelunkery stripes' cellular noise: points searched " + dev.denismasterherobrine.generatoraccelerator2.compat.spelunkery.SpelunkeryCells.searched()
                + ", answers taken from the other noise's search " + dev.denismasterherobrine.generatoraccelerator2.compat.spelunkery.SpelunkeryCells.shared());
        }
        if (dev.denismasterherobrine.generatoraccelerator2.compat.zeta.ZetaClusters.PARTS.sum() > 0) {
            send(source, "Zeta cluster parts walked by GA2 " + dev.denismasterherobrine.generatoraccelerator2.compat.zeta.ZetaClusters.PARTS.sum() + " (cluster out of reach of the chunk "
                + dev.denismasterherobrine.generatoraccelerator2.compat.zeta.ZetaClusters.OUT_OF_REACH.sum() + "), blocks asked whether a stone may go there "
                + dev.denismasterherobrine.generatoraccelerator2.compat.zeta.ZetaClusters.ASKED.sum());
        }
        if (dev.denismasterherobrine.generatoraccelerator2.compat.supplementaries.Barnacles.PLACEMENTS.sum() > 0) {
            send(source, "Supplementaries' barnacles searched once per direction: " + dev.denismasterherobrine.generatoraccelerator2.compat.supplementaries.Barnacles.PLACEMENTS.sum()
                + " placements, rounds left out " + dev.denismasterherobrine.generatoraccelerator2.compat.supplementaries.Barnacles.ROUNDS_SKIPPED.sum());
        }
        send(source, "random patch tries whose inner feature only reads: " + dev.denismasterherobrine.generatoraccelerator2.features.placement.PatchRepeats.TRIED.sum()
            + ", answered from an earlier try at the same position " + dev.denismasterherobrine.generatoraccelerator2.features.placement.PatchRepeats.SKIPPED.sum()
            + (dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets.allowed(dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets.PATCH_REPEATS) ? "" : " (off: another mod changes it)"));
        send(source, "structure references: parallel " + ParallelSteps.REFERENCES_ASYNC.sum() + ", inline " + ParallelSteps.REFERENCES_INLINE.sum()
            + "; light initializations on the pool " + ParallelSteps.LIGHT_INIT_ASYNC.sum());
        send(source, "light: fast engine " + (dev.denismasterherobrine.generatoraccelerator2.light.FastLight.on ? "on" : "off")
            + "; saving: sections packed " + dev.denismasterherobrine.generatoraccelerator2.storage.FastPack.PACKED.sum()
            + ", chunks compressed on the pool " + dev.denismasterherobrine.generatoraccelerator2.storage.Precompress.USED.sum()
            + ", by the IO thread " + dev.denismasterherobrine.generatoraccelerator2.storage.Precompress.INLINE.sum()
            + " (pool tasks taken back " + dev.denismasterherobrine.generatoraccelerator2.storage.Precompress.SKIPPED.sum()
            + ", waited for " + dev.denismasterherobrine.generatoraccelerator2.storage.Precompress.WAITED.sum() + ")"
            + ", region old-chunk scans " + dev.denismasterherobrine.generatoraccelerator2.storage.RegionScan.SCANS.sum());
        send(source, dev.denismasterherobrine.generatoraccelerator2.storage.SavePrep.stats());
        send(source, dev.denismasterherobrine.generatoraccelerator2.storage.RegionSync.stats());
        send(source, dev.denismasterherobrine.generatoraccelerator2.structure.TemplatePlacement.stats());
        send(source, dev.denismasterherobrine.generatoraccelerator2.storage.LoadPrep.stats());
        long aliased = dev.denismasterherobrine.generatoraccelerator2.compat.blueprint.BlueprintClimate.ALIASED.sum();
        if (aliased > 0) {
            long verified = dev.denismasterherobrine.generatoraccelerator2.compat.blueprint.BlueprintClimate.VERIFIED.sum();
            send(source, "Blueprint's slice samplers served by the compiled climate program: " + aliased
                + (verified > 0 ? "; target points verified " + verified + ", differing " + dev.denismasterherobrine.generatoraccelerator2.compat.blueprint.BlueprintClimate.DIFFERING.sum() : ""));
        }
        long probes = dev.denismasterherobrine.generatoraccelerator2.structure.HeightProbes.ASKED.sum();
        if (probes > 0) {
            send(source, "structure height probes: " + probes + " asked, " + dev.denismasterherobrine.generatoraccelerator2.structure.HeightProbes.REMEMBERED.sum()
                + " answered from earlier probes of the same column; surface levels by the compiled walk "
                + dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.SurfaceLevels.COLUMNS.sum());
        }
        long outsideAsked = dev.denismasterherobrine.generatoraccelerator2.biome.BiomeAnswers.ASKED.sum();
        if (outsideAsked > 0) {
            send(source, "biomes asked outside a region's chunks: " + outsideAsked + ", answered from earlier answers "
                + (outsideAsked - dev.denismasterherobrine.generatoraccelerator2.biome.BiomeAnswers.LOOKED_UP.sum()) + ", not kept " + dev.denismasterherobrine.generatoraccelerator2.biome.BiomeAnswers.UNSURE.sum()
                + " (searches with another leaf as near: GA2's " + dev.denismasterherobrine.generatoraccelerator2.biome.SearchTies.REASONS[0].sum()
                + ", Biolith's first answer " + dev.denismasterherobrine.generatoraccelerator2.biome.SearchTies.REASONS[1].sum()
                + ", Biolith's second " + dev.denismasterherobrine.generatoraccelerator2.biome.SearchTies.REASONS[2].sum()
                + "; searches GA2 does not see " + dev.denismasterherobrine.generatoraccelerator2.biome.SearchTies.REASONS[3].sum() + ")");
        }
        long readRegions = dev.denismasterherobrine.generatoraccelerator2.features.region.RegionReads.REGIONS.sum();
        long layerSearches = dev.denismasterherobrine.generatoraccelerator2.features.placement.EveryLayer.CALLS.sum();
        if (readRegions + layerSearches > 0) {
            long readsVerified = dev.denismasterherobrine.generatoraccelerator2.features.region.RegionReads.VERIFIED.sum();
            long layersVerified = dev.denismasterherobrine.generatoraccelerator2.features.placement.EveryLayer.VERIFIED.sum();
            send(source, "regions reading in place: " + readRegions + "; layer placements transcribed: " + layerSearches
                + (readsVerified > 0 ? "; reads verified " + readsVerified + ", differing " + dev.denismasterherobrine.generatoraccelerator2.features.region.RegionReads.DIFFERING.sum() : "")
                + (layersVerified > 0 ? "; layer scans verified " + layersVerified + ", differing " + dev.denismasterherobrine.generatoraccelerator2.features.placement.EveryLayer.DIFFERING.sum() : ""));
        }
        long biomePrograms = dev.denismasterherobrine.generatoraccelerator2.surface.compile.SurfaceProgram.BIOME_PROGRAMS.sum();
        if (biomePrograms > 0) {
            send(source, "surface rules compiled per biome: " + biomePrograms + " programs (shared by biomes with the same folded rules "
                + dev.denismasterherobrine.generatoraccelerator2.surface.compile.SurfaceProgram.BIOME_PROGRAMS_SHARED.sum() + " times), picked "
                + dev.denismasterherobrine.generatoraccelerator2.surface.compile.SurfaceProgram.BIOME_PICKS.sum() + " times; rules asked as a new sequence of known rules "
                + dev.denismasterherobrine.generatoraccelerator2.surface.SurfaceBuilder.SHAPE_HITS.sum() + " times");
        }
        long columnAnswers = dev.denismasterherobrine.generatoraccelerator2.biome.FlatClimateTree.GROUP_ANSWERED.sum();
        if (columnAnswers > 0 || dev.denismasterherobrine.generatoraccelerator2.biome.ClimateSearch.VERIFIED.get() > 0) {
            long fills = dev.denismasterherobrine.generatoraccelerator2.biome.FlatClimateTree.GROUP_FILLS.sum();
            send(source, String.format(java.util.Locale.ROOT, "climate searches answered per quart column: %,d (walked for shared distances %,d, trees without groups %,d, out of range %,d; columns filled %,d, %.1f nodes each; turned off for a while %,d times)",
                columnAnswers, dev.denismasterherobrine.generatoraccelerator2.biome.FlatClimateTree.GROUP_WALKED.sum(),
                dev.denismasterherobrine.generatoraccelerator2.biome.FlatClimateTree.WALK_NO_GROUPS.sum(), dev.denismasterherobrine.generatoraccelerator2.biome.FlatClimateTree.WALK_OUT_OF_RANGE.sum(), fills,
                fills == 0 ? 0.0 : (double) dev.denismasterherobrine.generatoraccelerator2.biome.FlatClimateTree.FILL_NODES.sum() / fills, dev.denismasterherobrine.generatoraccelerator2.biome.FlatClimateTree.COLUMNS_OFF.sum())
                + (dev.denismasterherobrine.generatoraccelerator2.biome.ClimateSearch.VERIFIED.get() > 0 ? "; verified against the walk "
                    + dev.denismasterherobrine.generatoraccelerator2.biome.ClimateSearch.VERIFIED.get() + ", differing "
                    + dev.denismasterherobrine.generatoraccelerator2.biome.ClimateSearch.DIFFERING.get() : ""));
        }
        if (dev.denismasterherobrine.generatoraccelerator2.biome.FusedLookup.VERIFIED.get() > 0) {
            send(source, "verify fused biome lookups: " + dev.denismasterherobrine.generatoraccelerator2.biome.FusedLookup.VERIFIED.get()
                + " compared with the sample and search, differing " + dev.denismasterherobrine.generatoraccelerator2.biome.FusedLookup.DIFFERING.get());
        }
        // the trees with the most column fills: nodes, depth groups, fills, searches answered from them
        java.util.List<dev.denismasterherobrine.generatoraccelerator2.biome.FlatClimateTree> trees = dev.denismasterherobrine.generatoraccelerator2.biome.FlatClimateTree.all();
        trees.sort(java.util.Comparator.comparingInt((dev.denismasterherobrine.generatoraccelerator2.biome.FlatClimateTree t) -> t.statFills).reversed());
        for (int i = 0; i < Math.min(8, trees.size()) && trees.get(i).statFills > 0; i++) {
            var t = trees.get(i);
            send(source, String.format(java.util.Locale.ROOT, "  climate tree of %,d nodes, %d depth groups: %,d columns filled, %,d searches answered from them", t.nodes(), t.groupCount(),
                t.statFills, t.statAnswers));
        }
        long biolithFlat = dev.denismasterherobrine.generatoraccelerator2.compat.biolith.BiolithSearch.FLAT.sum();
        long biolithOwn = dev.denismasterherobrine.generatoraccelerator2.compat.biolith.BiolithSearch.BIOLITH.sum();
        if (biolithFlat + biolithOwn > 0) {
            long verified = dev.denismasterherobrine.generatoraccelerator2.compat.biolith.BiolithSearch.VERIFIED.get();
            send(source, "Biolith's climate searches: through GA2's flat tree " + biolithFlat + ", its own " + biolithOwn
                + (verified > 0 ? "; verified " + verified + ", differing " + dev.denismasterherobrine.generatoraccelerator2.compat.biolith.BiolithSearch.DIFFERING.get() : ""));
        }
        long regionComputed = dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched.LithostitchedRegions.COMPUTED.sum();
        long regionRemembered = dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched.LithostitchedRegions.REMEMBERED.sum();
        if (regionComputed + regionRemembered > 0) {
            send(source, "Lithostitched's region function (column-only): computed " + regionComputed + ", remembered " + regionRemembered);
        }
        long walks = dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched.LithostitchedInjectors.filtered();
        if (walks > 0) {
            long checked = dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched.LithostitchedInjectors.CHECKED.sum();
            send(source, "Lithostitched's partial replacements: " + walks + " walks over the biome's subset, "
                + dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched.LithostitchedInjectors.skipped() + " replacements skipped"
                + (checked > 0 ? "; noted biome checked " + checked + ", wrong " + dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched.LithostitchedInjectors.MISNOTED.sum() : ""));
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
