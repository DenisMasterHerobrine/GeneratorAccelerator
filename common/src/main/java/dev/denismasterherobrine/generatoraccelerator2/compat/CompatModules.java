package dev.denismasterherobrine.generatoraccelerator2.compat;

import dev.denismasterherobrine.generatoraccelerator2.compat.biolith.BiolithMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.biolith.BiolithSurface;
import dev.denismasterherobrine.generatoraccelerator2.compat.biomeswevegone.BiomesWeveGoneMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.biomeswevegone.BiomesWeveGoneRules;
import dev.denismasterherobrine.generatoraccelerator2.compat.biomeswevegone.BiomesWeveGoneSurface;
import dev.denismasterherobrine.generatoraccelerator2.compat.biomeswevegone.BiomesWeveGoneTerrain;
import dev.denismasterherobrine.generatoraccelerator2.compat.c2me.C2MEMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched.LithostitchedDensity;
import dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched.LithostitchedMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.naturespirit.NaturesSpiritMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.naturespirit.NaturesSpiritSurface;
import dev.denismasterherobrine.generatoraccelerator2.compat.regionsunexplored.RegionsUnexploredCarvers;
import dev.denismasterherobrine.generatoraccelerator2.compat.regionsunexplored.RegionsUnexploredMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.repurposedstructures.RepurposedStructuresFeatures;
import dev.denismasterherobrine.generatoraccelerator2.compat.repurposedstructures.RepurposedStructuresMixins;
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
        if (Compat.isLoaded(dev.denismasterherobrine.generatoraccelerator2.compat.chunky.ChunkyRequests.MOD_ID)) {
            dev.denismasterherobrine.generatoraccelerator2.compat.chunky.ChunkyRequests.register();
        }
        if (Compat.isLoaded(dev.denismasterherobrine.generatoraccelerator2.compat.fabricapi.FabricApiMixins.MOD_ID)) {
            dev.denismasterherobrine.generatoraccelerator2.compat.fabricapi.FabricApiMixins.register();
        }
        if (Compat.isLoaded(NaturesSpiritMixins.MOD_ID)) {
            NaturesSpiritSurface.register();
        }
        if (Compat.isLoaded(dev.denismasterherobrine.generatoraccelerator2.compat.eclipticseasons.EclipticSeasonsMixins.MOD_ID)) {
            dev.denismasterherobrine.generatoraccelerator2.compat.eclipticseasons.EclipticSeasonsSurface.register();
        }
        if (Compat.isLoaded(BiomesWeveGoneMixins.MOD_ID)) {
            BiomesWeveGoneSurface.register();
            BiomesWeveGoneRules.register();
            BiomesWeveGoneTerrain.register();
        }
        if (Compat.isLoaded(dev.denismasterherobrine.generatoraccelerator2.compat.vanillabackport.VanillaBackportRules.MOD_ID)) {
            dev.denismasterherobrine.generatoraccelerator2.compat.vanillabackport.VanillaBackportRules.register();
        }
        if (Compat.isLoaded(BiolithMixins.MOD_ID)) {
            BiolithSurface.register();
            dev.denismasterherobrine.generatoraccelerator2.compat.biolith.BiolithParameters.register();
            // nothing to set up: Biolith's lookup answers from the position and the world's seed, set before the first chunk
            CompatAdapters.register(HookTargets.BIOME_ANSWERS, BiolithMixins.PARAMETERS, () -> true);
        }
        if (Compat.isLoaded(dev.denismasterherobrine.generatoraccelerator2.compat.tectonic.TectonicMixins.MOD_ID)) {
            dev.denismasterherobrine.generatoraccelerator2.compat.tectonic.TectonicSnow.register();
        }
        if (Compat.isLoaded(RegionsUnexploredMixins.MOD_ID)) {
            RegionsUnexploredCarvers.register();
        }
        if (Compat.isLoaded(RepurposedStructuresMixins.MOD_ID)) {
            RepurposedStructuresFeatures.register();
        }
        if (Compat.isLoaded(LithostitchedDensity.MOD_ID)) {
            LithostitchedDensity.register();
            dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched.LithostitchedFunctions.register();
            // nothing to set up: the fossil probe's early answer leaves Lithostitched's wrapped call to vanilla's method
            CompatAdapters.register(HookTargets.STRUCTURE_FOSSIL_PROBE, LithostitchedMixins.NETHER_FOSSIL, () -> true);
            // nothing to set up: GPU biomes are for a plain multi-noise source only, which the wrapping passes through
            CompatAdapters.register(HookTargets.GPU_BIOMES, LithostitchedMixins.TERRABLENDER_RESOLVER, () -> true);
            // the injector source answers from the position: its base source's answer, then injectors of noise and regions
            dev.denismasterherobrine.generatoraccelerator2.biome.BiomeAnswers.knownSource(source ->
                dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched.LithostitchedGpuBiomes.isInjector(source)
                    && dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched.LithostitchedGpuBiomes.base(source) != null);
        }
        if (Compat.isLoaded(dev.denismasterherobrine.generatoraccelerator2.compat.blueprint.BlueprintClimate.MOD_ID)) {
            dev.denismasterherobrine.generatoraccelerator2.compat.blueprint.BlueprintClimate.register();
        }
        if (Compat.isLoaded(dev.denismasterherobrine.generatoraccelerator2.compat.quark.QuarkMixins.MOD_ID)) {
            // nothing to set up: the region chunk cache runs the original lookup, with Quark's change, for anything the cache has not seen
            CompatAdapters.register(FeatureTargets.compatKey(FeatureTargets.REGION), dev.denismasterherobrine.generatoraccelerator2.compat.quark.QuarkMixins.WORLD_GEN_REGION, () -> true);
        }
        if (Compat.isLoaded(dev.denismasterherobrine.generatoraccelerator2.compat.confluence.ConfluenceMixins.MOD_ID)) {
            dev.denismasterherobrine.generatoraccelerator2.compat.confluence.ConfluenceFeatures.register();
        }
        if (Compat.isLoaded(dev.denismasterherobrine.generatoraccelerator2.compat.eternalstarlight.EternalStarlightMixins.MOD_ID)) {
            dev.denismasterherobrine.generatoraccelerator2.compat.eternalstarlight.EternalStarlightFeatures.register();
        }
        if (Compat.isLoaded(dev.denismasterherobrine.generatoraccelerator2.compat.modernfix.ModernFixMixins.MOD_ID)) {
            // nothing to set up: ModernFix's stronghold cache keeps vanilla's ring positions. The ring bound needs nothing more
            CompatAdapters.register(HookTargets.STRUCTURE_RING_BOUND, dev.denismasterherobrine.generatoraccelerator2.compat.modernfix.ModernFixMixins.STRONGHOLD_CACHE, () -> true);
            // nothing to set up: without ModernFix's cache, a temperature is still getHeightAdjustedTemperature's. The GPU computes that
            CompatAdapters.register(HookTargets.GPU_SURFACE, dev.denismasterherobrine.generatoraccelerator2.compat.modernfix.ModernFixMixins.BIOME_TEMPERATURE, () -> true);
        }
        if (Compat.isLoaded(dev.denismasterherobrine.generatoraccelerator2.compat.owo.OwoMixins.MOD_ID)) {
            dev.denismasterherobrine.generatoraccelerator2.compat.owo.OwoOres.register();
        }
        // mods whose changes to ChunkSerializer.read leave alone the tags that the prepared parts of a loaded chunk come from
        ChunkLoadMixins.register();
        if (Compat.isLoaded(dev.denismasterherobrine.generatoraccelerator2.compat.cobblemon.CobblemonMixins.MOD_ID)) {
            dev.denismasterherobrine.generatoraccelerator2.compat.cobblemon.CobblemonFeatures.register();
        }
        if (Compat.isLoaded(dev.denismasterherobrine.generatoraccelerator2.compat.winterly.WinterlyMixins.MOD_ID)) {
            dev.denismasterherobrine.generatoraccelerator2.compat.winterly.WinterlyFeatures.register();
        }
        if (Compat.isLoaded(dev.denismasterherobrine.generatoraccelerator2.compat.redirected.RedirectedMixins.MOD_ID)) {
            dev.denismasterherobrine.generatoraccelerator2.compat.redirected.RedirectedMixins.register();
        }
        if (Compat.isLoaded(dev.denismasterherobrine.generatoraccelerator2.compat.spawn.SpawnMixins.MOD_ID)) {
            dev.denismasterherobrine.generatoraccelerator2.compat.spawn.SpawnBiomes.register();
        }
        if (Compat.isLoaded(dev.denismasterherobrine.generatoraccelerator2.compat.citadel.CitadelMixins.MOD_ID)) {
            dev.denismasterherobrine.generatoraccelerator2.compat.citadel.CitadelRules.register();
        }
        if (Compat.isLoaded(dev.denismasterherobrine.generatoraccelerator2.compat.alexscaves.AlexsCavesMixins.MOD_ID)) {
            dev.denismasterherobrine.generatoraccelerator2.compat.alexscaves.AlexsCavesChunks.register();
            dev.denismasterherobrine.generatoraccelerator2.compat.alexscaves.AlexsCavesRules.register();
            dev.denismasterherobrine.generatoraccelerator2.compat.alexscaves.AlexsCavesRules.registerBiomeAnswers();
        }
        if (Compat.isLoaded(dev.denismasterherobrine.generatoraccelerator2.compat.betterchunkloading.BetterChunkLoadingMixins.MOD_ID)) {
            dev.denismasterherobrine.generatoraccelerator2.compat.betterchunkloading.BetterChunkLoadingMixins.register();
        }
        if (Compat.isLoaded(dev.denismasterherobrine.generatoraccelerator2.compat.pastel.PastelMixins.MOD_ID)) {
            dev.denismasterherobrine.generatoraccelerator2.compat.pastel.PastelGeodes.register();
        }
        if (Compat.isLoaded(dev.denismasterherobrine.generatoraccelerator2.compat.variantsandventures.VariantsAndVenturesMixins.MOD_ID)) {
            dev.denismasterherobrine.generatoraccelerator2.compat.variantsandventures.VariantsAndVenturesFeatures.register();
        }
        if (Compat.isLoaded(dev.denismasterherobrine.generatoraccelerator2.compat.lithium.LithiumMixins.MOD_ID)) {
            dev.denismasterherobrine.generatoraccelerator2.compat.lithium.LithiumMixins.register();
        }
        if (Compat.isLoaded(C2MEMixins.MOD_ID)) {
            C2MEMixins.register();
        }
        if (Compat.isLoaded(dev.denismasterherobrine.generatoraccelerator2.compat.terrablender.TerraBlenderMixins.MOD_ID)) {
            // the GPU searches the region trees as TerraBlender does, which needs TerraBlender's searches to use vanilla's distance
            for (KnownMixins.Entry e : dev.denismasterherobrine.generatoraccelerator2.compat.terrablender.TerraBlenderMixins.ENTRIES) {
                if (e.hook().equals(HookTargets.GPU_BIOMES)) {
                    CompatAdapters.register(e.hook(), e.mixin(), () -> dev.denismasterherobrine.generatoraccelerator2.compat.terrablender.TerraBlenderBiomes.available()
                        && dev.denismasterherobrine.generatoraccelerator2.compat.terrablender.TerraBlenderMixins.distanceSearch());
                }
            }
            // nothing to set up: TerraBlender's lookup answers from the position and the regions of the world's seed
            CompatAdapters.register(HookTargets.BIOME_ANSWERS, dev.denismasterherobrine.generatoraccelerator2.compat.terrablender.TerraBlenderMixins.BIOME_SOURCE, () -> true);
        }
        if (Compat.isLoaded(dev.denismasterherobrine.generatoraccelerator2.compat.twilightforest.TwilightForestCoremods.MOD_ID)) {
            dev.denismasterherobrine.generatoraccelerator2.compat.twilightforest.TwilightForestSurface.register();
        }
    }
}
