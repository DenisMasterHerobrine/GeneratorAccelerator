package dev.denismasterherobrine.generatoraccelerator2.compat;

import dev.denismasterherobrine.generatoraccelerator2.biome.BiomeAnswers;
import dev.denismasterherobrine.generatoraccelerator2.compat.atmospheric.AtmosphericFeatures;
import dev.denismasterherobrine.generatoraccelerator2.compat.atmospheric.AtmosphericMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.alexscaves.AlexsCavesMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.alexscaves.AlexsCavesRules;
import dev.denismasterherobrine.generatoraccelerator2.compat.blockswap.BlockSwapLoads;
import dev.denismasterherobrine.generatoraccelerator2.compat.blockswap.BlockSwapMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.blockswap.BlockSwapSections;
import dev.denismasterherobrine.generatoraccelerator2.compat.create.CreateMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.environmental.EnvironmentalMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.environmental.EnvironmentalSurface;
import dev.denismasterherobrine.generatoraccelerator2.compat.formations.FormationsMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.hybridaquatic.HybridAquaticMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.betterchunkloading.BetterChunkLoadingMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.biolith.BiolithMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.biolith.BiolithParameters;
import dev.denismasterherobrine.generatoraccelerator2.compat.biolith.BiolithSurface;
import dev.denismasterherobrine.generatoraccelerator2.compat.biomeswevegone.BiomesWeveGoneMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.biomeswevegone.BiomesWeveGoneRules;
import dev.denismasterherobrine.generatoraccelerator2.compat.biomeswevegone.BiomesWeveGoneSurface;
import dev.denismasterherobrine.generatoraccelerator2.compat.biomeswevegone.BiomesWeveGoneTerrain;
import dev.denismasterherobrine.generatoraccelerator2.compat.blueprint.BlueprintClimate;
import dev.denismasterherobrine.generatoraccelerator2.compat.c2me.C2MEMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.chunky.ChunkyRequests;
import dev.denismasterherobrine.generatoraccelerator2.compat.cobblemon.CobblemonFeatures;
import dev.denismasterherobrine.generatoraccelerator2.compat.cobblemon.CobblemonMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.confluence.ConfluenceFeatures;
import dev.denismasterherobrine.generatoraccelerator2.compat.confluence.ConfluenceMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.eclipticseasons.EclipticSeasonsMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.eclipticseasons.EclipticSeasonsSurface;
import dev.denismasterherobrine.generatoraccelerator2.compat.fabricapi.FabricApiMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.adastra.AdAstraMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.diggerhelmet.DiggerHelmetLight;
import dev.denismasterherobrine.generatoraccelerator2.compat.diggerhelmet.DiggerHelmetMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.ftbchunks.FtbChunksMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.lootr.LootrMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.railways.RailwaysMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.tfc.TfcMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.gtceu.GtceuMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.gtceu.GtceuVeins;
import dev.denismasterherobrine.generatoraccelerator2.compat.lithium.LithiumMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.modernfix.ModernFixMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.saturn.SaturnMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.naturespirit.NaturesSpiritMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.naturespirit.NaturesSpiritSurface;
import dev.denismasterherobrine.generatoraccelerator2.compat.owo.OwoMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.owo.OwoOres;
import dev.denismasterherobrine.generatoraccelerator2.compat.redirected.RedirectedMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.regionsunexplored.RegionsUnexploredCarvers;
import dev.denismasterherobrine.generatoraccelerator2.compat.regionsunexplored.RegionsUnexploredMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.repurposedstructures.RepurposedStructuresFeatures;
import dev.denismasterherobrine.generatoraccelerator2.compat.repurposedstructures.RepurposedStructuresMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.spawn.SpawnBiomes;
import dev.denismasterherobrine.generatoraccelerator2.compat.spawn.SpawnMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.spectrum.SpectrumGeodes;
import dev.denismasterherobrine.generatoraccelerator2.compat.spectrum.SpectrumMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.tectonic.TectonicMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.tectonic.TectonicSnow;
import dev.denismasterherobrine.generatoraccelerator2.compat.terrablender.TerraBlenderBiomes;
import dev.denismasterherobrine.generatoraccelerator2.compat.terrablender.TerraBlenderMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.vanillabackport.VanillaBackportLoads;
import dev.denismasterherobrine.generatoraccelerator2.compat.vanillabackport.VanillaBackportRules;
import dev.denismasterherobrine.generatoraccelerator2.compat.variantsandventures.VariantsAndVenturesFeatures;
import dev.denismasterherobrine.generatoraccelerator2.compat.variantsandventures.VariantsAndVenturesMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.winterly.WinterlyFeatures;
import dev.denismasterherobrine.generatoraccelerator2.compat.winterly.WinterlyMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.yungscavebiomes.YungsCaveBiomesMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.yungscavebiomes.FrostedCaves;
import dev.denismasterherobrine.generatoraccelerator2.compat.yungscavebiomes.YungsCaveBiomesNoise;
import dev.denismasterherobrine.generatoraccelerator2.dfc.Compat;

/**
 * Sets up the runtime side of GA2's compat with other worldgen mods, one {@code compat/<modid>} package each, at mod
 * construction (before any world loads). Each module only registers code with GA2's extension points: surface column
 * hooks and rule lowerings, density function lowerings, carver block tests. Mixins on the mods' behalf live in each
 * package's {@code mixin} subpackage, in a mixin config applied only with that mod loaded ({@link CompatMixinPlugin}).
 */
public final class CompatModules {
    private CompatModules() {}

    public static void init() {
        if (Compat.isLoaded(ChunkyRequests.MOD_ID)) {
            ChunkyRequests.register();
        }
        if (Compat.isLoaded(FabricApiMixins.MOD_ID) || Compat.isLoaded(FabricApiMixins.FORGE_MOD_ID) || Compat.isLoaded(FabricApiMixins.FLUIDS_MOD_ID)) {
            FabricApiMixins.register();
        }
        if (Compat.isLoaded(NaturesSpiritMixins.MOD_ID)) {
            NaturesSpiritSurface.register();
        }
        if (Compat.isLoaded(EclipticSeasonsMixins.MOD_ID)) {
            EclipticSeasonsSurface.register();
        }
        if (Compat.isLoaded(BiomesWeveGoneMixins.MOD_ID)) {
            BiomesWeveGoneSurface.register();
            BiomesWeveGoneRules.register();
            BiomesWeveGoneTerrain.register();
        }
        if (Compat.isLoaded(VanillaBackportRules.MOD_ID)) {
            VanillaBackportRules.register();
            VanillaBackportLoads.register();
        }
        if (Compat.isLoaded(BiolithMixins.MOD_ID)) {
            BiolithSurface.register();
            BiolithParameters.register();
            // nothing to set up: Biolith's lookup answers from the position and the world's seed, set before the first chunk
            CompatAdapters.register(HookTargets.BIOME_ANSWERS, BiolithMixins.PARAMETERS, () -> true);
        }
        if (Compat.isLoaded(TectonicMixins.MOD_ID)) {
            TectonicSnow.register();
        }
        if (Compat.isLoaded(RegionsUnexploredMixins.MOD_ID)) {
            RegionsUnexploredCarvers.register();
        }
        if (Compat.isLoaded(RepurposedStructuresMixins.MOD_ID)) {
            RepurposedStructuresFeatures.register();
        }
        if (Compat.isLoaded(BlueprintClimate.MOD_ID)) {
            BlueprintClimate.register();
        }
        if (Compat.isLoaded(ConfluenceMixins.MOD_ID)) {
            ConfluenceFeatures.register();
        }
        if (Compat.isLoaded(ModernFixMixins.MOD_ID)) {
            // nothing to set up: ModernFix's stronghold cache keeps vanilla's ring positions. The ring bound needs nothing more
            CompatAdapters.register(HookTargets.STRUCTURE_RING_BOUND, ModernFixMixins.STRONGHOLD_CACHE, () -> true);
            // nothing to set up: without ModernFix's cache, a temperature is still getHeightAdjustedTemperature's. The GPU computes that
            CompatAdapters.register(HookTargets.GPU_SURFACE, ModernFixMixins.BIOME_TEMPERATURE, () -> true);
            // the same for the freeze and snow skip, which reads only the base temperature and the climate settings
            CompatAdapters.register(FeatureTargets.compatKey("climate"), ModernFixMixins.BIOME_TEMPERATURE, () -> true);
            // nothing to set up: ModernFix's answer runs on the server thread only, GA2's off it
            CompatAdapters.register(HookTargets.CHUNK_PRESENT_OFF_THREAD, ModernFixMixins.CHUNK_DEADLOCK, () -> true);
            CompatAdapters.register(HookTargets.CHUNK_PRESENT_OFF_THREAD, ModernFixMixins.CHUNK_DEADLOCK_FORGE, () -> true);
        }
        if (Compat.isLoaded(FtbChunksMixins.MOD_ID)) {
            // nothing to set up: FTB Chunks only removes directions from vanilla's spread
            CompatAdapters.register(HookTargets.FLUID_POST_PROCESS, FtbChunksMixins.FLUID_SPREAD, () -> true);
        }
        if (Compat.isLoaded(SaturnMixins.MOD_ID)) {
            SaturnMixins.register();
        }
        if (Compat.isLoaded(OwoMixins.MOD_ID)) {
            OwoOres.register();
        }
        // mods whose changes to ChunkSerializer.read leave alone the tags that the prepared parts of a loaded chunk come from
        ChunkLoadMixins.register();
        if (Compat.isLoaded(CobblemonMixins.MOD_ID)) {
            CobblemonFeatures.register();
        }
        if (Compat.isLoaded(AtmosphericMixins.MOD_ID)) {
            AtmosphericFeatures.register();
        }
        if (Compat.isLoaded(EnvironmentalMixins.MOD_ID)) {
            EnvironmentalSurface.register();
        }
        if (Compat.isLoaded(BlockSwapMixins.MOD_ID)) {
            BlockSwapSections.register();
            BlockSwapLoads.register();
        }
        if (Compat.isLoaded(FormationsMixins.MOD_ID)) {
            // nothing to set up: a placement with one of Formations' processors is never clipped (structure.TemplatePlacement)
            CompatAdapters.register(HookTargets.STRUCTURE_TEMPLATE_CLIP, FormationsMixins.TEMPLATE, () -> true);
        }
        // nothing to set up: these fluid tests only say no, and a still-water tick GA2 leaves out never gets an answer from them
        if (Compat.isLoaded(CreateMixins.MOD_ID)) {
            CompatAdapters.register(HookTargets.FLUID_POST_PROCESS, CreateMixins.WATER_WHEEL, () -> true);
        }
        if (Compat.isLoaded(AlexsCavesMixins.MOD_ID)) {
            CompatAdapters.register(HookTargets.FLUID_POST_PROCESS, AlexsCavesMixins.FLOWING_FLUID, () -> true);
            AlexsCavesRules.register();
        }
        if (Compat.isLoaded(HybridAquaticMixins.MOD_ID)) {
            CompatAdapters.register(HookTargets.FLUID_POST_PROCESS, HybridAquaticMixins.FLOWING_FLUID, () -> true);
            // nothing to set up: the placement is set while the server starts, before biome answers are kept (BiomeAnswers.ready)
            CompatAdapters.register(HookTargets.BIOME_ANSWERS, HybridAquaticMixins.BIOME_SOURCE, () -> true);
        }
        if (Compat.isLoaded(WinterlyMixins.MOD_ID)) {
            WinterlyFeatures.register();
        }
        if (Compat.isLoaded(YungsCaveBiomesMixins.MOD_ID)) {
            YungsCaveBiomesNoise.register();
            // the Frosted Caves water, where GA2 took the mod's aquifer handler over (Takeover)
            FrostedCaves.register();
        }
        if (Compat.isLoaded(RedirectedMixins.MOD_ID) || Compat.isLoaded(RedirectedMixins.REDIRECTOR_ID)) {
            RedirectedMixins.register();
        }
        if (Compat.isLoaded(SpawnMixins.MOD_ID)) {
            SpawnBiomes.register();
        }
        if (Compat.isLoaded(BetterChunkLoadingMixins.MOD_ID)) {
            BetterChunkLoadingMixins.register();
        }
        if (Compat.isLoaded(SpectrumMixins.MOD_ID)) {
            SpectrumGeodes.register();
        }
        if (Compat.isLoaded(VariantsAndVenturesMixins.MOD_ID)) {
            VariantsAndVenturesFeatures.register();
        }
        if (Compat.isLoaded(LithiumMixins.MOD_ID) || LithiumMixins.PORTS.stream().anyMatch(Compat::isLoaded)) {
            LithiumMixins.register();
        }
        if (Compat.isLoaded(C2MEMixins.MOD_ID)) {
            C2MEMixins.register();
        }
        if (Compat.isLoaded(TerraBlenderMixins.MOD_ID)) {
            // the GPU searches the region trees as TerraBlender does, which needs TerraBlender's searches to use vanilla's distance
            for (KnownMixins.Entry e : TerraBlenderMixins.ENTRIES) {
                if (e.hook().equals(HookTargets.GPU_BIOMES)) {
                    CompatAdapters.register(e.hook(), e.mixin(), () -> TerraBlenderBiomes.available()
                        && TerraBlenderMixins.distanceSearch());
                }
            }
            // nothing to set up: TerraBlender's lookup answers from the position and the regions of the world's seed
            CompatAdapters.register(HookTargets.BIOME_ANSWERS, TerraBlenderMixins.BIOME_SOURCE, () -> true);
        }
        if (Compat.isLoaded(GtceuMixins.MOD_ID)) {
            GtceuVeins.register();
        }
        if (Compat.isLoaded(LootrMixins.MOD_ID)) {
            // nothing to set up: GA2 asks forState, which runs Lootr's handler
            CompatAdapters.register(HookTargets.STATE_POI_SKIP, LootrMixins.POI_TYPES, () -> true);
        }
        if (Compat.isLoaded(RailwaysMixins.MOD_ID)) {
            // nothing to set up: the skipped biomes return before the track test
            CompatAdapters.register(FeatureTargets.compatKey("climate"), RailwaysMixins.SNOW_ON_TRACKS, () -> true);
        }
        if (Compat.isLoaded(TfcMixins.MOD_ID)) {
            // nothing to set up: TFC's renderer change only hides more faces
            CompatAdapters.register(HookTargets.FLUID_HIDDEN_WATER, TfcMixins.LIQUID_RENDERER, () -> true);
        }
        if (Compat.isLoaded(AdAstraMixins.MOD_ID)) {
            // nothing to set up: the water GA2 leaves alone gets the same tick result with Ad Astra's changes
            CompatAdapters.register(HookTargets.FLUID_POST_PROCESS, AdAstraMixins.FLUIDS, () -> true);
        }
        if (Compat.isLoaded(DiggerHelmetMixins.MOD_ID)) {
            DiggerHelmetLight.register();
        }
    }
}
