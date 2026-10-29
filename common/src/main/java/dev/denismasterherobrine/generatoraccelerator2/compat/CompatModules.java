package dev.denismasterherobrine.generatoraccelerator2.compat;

import dev.denismasterherobrine.generatoraccelerator2.biome.BiomeAnswers;
import dev.denismasterherobrine.generatoraccelerator2.compat.alexscaves.AlexsCavesChunks;
import dev.denismasterherobrine.generatoraccelerator2.compat.alexscaves.AlexsCavesMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.alexscaves.AlexsCavesRules;
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
import dev.denismasterherobrine.generatoraccelerator2.compat.citadel.CitadelMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.citadel.CitadelRules;
import dev.denismasterherobrine.generatoraccelerator2.compat.cobblemon.CobblemonFeatures;
import dev.denismasterherobrine.generatoraccelerator2.compat.cobblemon.CobblemonMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.confluence.ConfluenceFeatures;
import dev.denismasterherobrine.generatoraccelerator2.compat.confluence.ConfluenceMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.eclipticseasons.EclipticSeasonsMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.eclipticseasons.EclipticSeasonsSurface;
import dev.denismasterherobrine.generatoraccelerator2.compat.eternalstarlight.EternalStarlightFeatures;
import dev.denismasterherobrine.generatoraccelerator2.compat.eternalstarlight.EternalStarlightMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.fabricapi.FabricApiMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.ftbchunks.FtbChunksMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.gtceu.GtceuMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.gtceu.GtceuVeins;
import dev.denismasterherobrine.generatoraccelerator2.compat.lithium.LithiumMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched.LithostitchedDensity;
import dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched.LithostitchedFunctions;
import dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched.LithostitchedGpuBiomes;
import dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched.LithostitchedMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.modernfix.ModernFixMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.naturespirit.NaturesSpiritMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.naturespirit.NaturesSpiritSurface;
import dev.denismasterherobrine.generatoraccelerator2.compat.owo.OwoMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.owo.OwoOres;
import dev.denismasterherobrine.generatoraccelerator2.compat.pastel.PastelGeodes;
import dev.denismasterherobrine.generatoraccelerator2.compat.pastel.PastelMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.quark.QuarkMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.sodiumextra.SodiumExtraLight;
import dev.denismasterherobrine.generatoraccelerator2.compat.sodiumextra.SodiumExtraMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.redirected.RedirectedMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.regionsunexplored.RegionsUnexploredCarvers;
import dev.denismasterherobrine.generatoraccelerator2.compat.regionsunexplored.RegionsUnexploredMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.repurposedstructures.RepurposedStructuresFeatures;
import dev.denismasterherobrine.generatoraccelerator2.compat.repurposedstructures.RepurposedStructuresMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.spawn.SpawnBiomes;
import dev.denismasterherobrine.generatoraccelerator2.compat.spawn.SpawnMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.spectrum.SpectrumMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.tectonic.TectonicMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.tectonic.TectonicSnow;
import dev.denismasterherobrine.generatoraccelerator2.compat.terrablender.TerraBlenderBiomes;
import dev.denismasterherobrine.generatoraccelerator2.compat.terrablender.TerraBlenderMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.twilightforest.TwilightForestCoremods;
import dev.denismasterherobrine.generatoraccelerator2.compat.twilightforest.TwilightForestSurface;
import dev.denismasterherobrine.generatoraccelerator2.compat.vanillabackport.VanillaBackportRules;
import dev.denismasterherobrine.generatoraccelerator2.compat.variantsandventures.VariantsAndVenturesFeatures;
import dev.denismasterherobrine.generatoraccelerator2.compat.variantsandventures.VariantsAndVenturesMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.winterly.WinterlyFeatures;
import dev.denismasterherobrine.generatoraccelerator2.compat.winterly.WinterlyMixins;
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
        if (Compat.isLoaded(SodiumExtraMixins.MOD_ID)) {
            // the layers check Sodium Extra's light updates option on every update
            CompatAdapters.register(HookTargets.LIGHT_LAYERS, SodiumExtraMixins.LIGHT_ENGINE, SodiumExtraLight::register);
        }
        if (Compat.isLoaded(ChunkyRequests.MOD_ID)) {
            ChunkyRequests.register();
        }
        if (Compat.isLoaded(FabricApiMixins.MOD_ID) || Compat.isLoaded(FabricApiMixins.FLUIDS_MOD_ID)) {
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
        if (Compat.isLoaded(LithostitchedDensity.MOD_ID)) {
            LithostitchedDensity.register();
            LithostitchedFunctions.register();
            // nothing to set up: the fossil probe's early answer leaves Lithostitched's wrapped call to vanilla's method
            CompatAdapters.register(HookTargets.STRUCTURE_FOSSIL_PROBE, LithostitchedMixins.NETHER_FOSSIL, () -> true);
            // nothing to set up: GPU biomes are for a plain multi-noise source only, which the wrapping passes through
            CompatAdapters.register(HookTargets.GPU_BIOMES, LithostitchedMixins.TERRABLENDER_RESOLVER, () -> true);
            // the injector source answers from the position: its base source's answer, then injectors of noise and regions
            BiomeAnswers.knownSource(source ->
                LithostitchedGpuBiomes.isInjector(source)
                    && LithostitchedGpuBiomes.base(source) != null);
        }
        if (Compat.isLoaded(BlueprintClimate.MOD_ID)) {
            BlueprintClimate.register();
        }
        if (Compat.isLoaded(QuarkMixins.MOD_ID)) {
            // nothing to set up: the region chunk cache runs the original lookup, with Quark's change, for anything the cache has not seen
            CompatAdapters.register(FeatureTargets.compatKey(FeatureTargets.REGION), QuarkMixins.WORLD_GEN_REGION, () -> true);
        }
        if (Compat.isLoaded(ConfluenceMixins.MOD_ID)) {
            ConfluenceFeatures.register();
        }
        if (Compat.isLoaded(EternalStarlightMixins.MOD_ID)) {
            EternalStarlightFeatures.register();
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
        if (Compat.isLoaded(OwoMixins.MOD_ID)) {
            OwoOres.register();
        }
        // mods whose changes to ChunkSerializer.read leave alone the tags that the prepared parts of a loaded chunk come from
        ChunkLoadMixins.register();
        if (Compat.isLoaded(CobblemonMixins.MOD_ID)) {
            CobblemonFeatures.register();
        }
        if (Compat.isLoaded(WinterlyMixins.MOD_ID)) {
            WinterlyFeatures.register();
        }
        if (Compat.isLoaded(RedirectedMixins.MOD_ID)) {
            RedirectedMixins.register();
        }
        if (Compat.isLoaded(SpawnMixins.MOD_ID)) {
            SpawnBiomes.register();
        }
        if (Compat.isLoaded(CitadelMixins.MOD_ID)) {
            CitadelRules.register();
        }
        if (Compat.isLoaded(AlexsCavesMixins.MOD_ID)) {
            AlexsCavesChunks.register();
            AlexsCavesRules.register();
            AlexsCavesRules.registerBiomeAnswers();
        }
        if (Compat.isLoaded(BetterChunkLoadingMixins.MOD_ID)) {
            BetterChunkLoadingMixins.register();
        }
        if (Compat.isLoaded(PastelMixins.MOD_ID)) {
            PastelGeodes.register();
        }
        if (Compat.isLoaded(SpectrumMixins.MOD_ID)) {
            SpectrumMixins.register();
        }
        if (Compat.isLoaded(VariantsAndVenturesMixins.MOD_ID)) {
            VariantsAndVenturesFeatures.register();
        }
        if (Compat.isLoaded(LithiumMixins.MOD_ID)) {
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
        if (Compat.isLoaded(TwilightForestCoremods.MOD_ID)) {
            TwilightForestSurface.register();
        }
        if (Compat.isLoaded(GtceuMixins.MOD_ID)) {
            GtceuVeins.register();
        }
    }
}
