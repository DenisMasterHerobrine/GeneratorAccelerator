package dev.denismasterherobrine.generatoraccelerator2.compat;

import dev.denismasterherobrine.generatoraccelerator2.compat.atmospheric.AtmosphericMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.alexscaves.AlexsCavesMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.blockswap.BlockSwapMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.create.CreateMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.environmental.EnvironmentalMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.formations.FormationsMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.hybridaquatic.HybridAquaticMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.vanillabackport.VanillaBackportMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.bettercaves.BetterCavesMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.betterchunkloading.BetterChunkLoadingMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.biolith.BiolithMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.biomeswevegone.BiomesWeveGoneMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.c2me.C2MEMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.cobblemon.CobblemonMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.confluence.ConfluenceMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.eclipticseasons.EclipticSeasonsMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.fabricapi.FabricApiMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.adastra.AdAstraMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.diggerhelmet.DiggerHelmetMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.ftbchunks.FtbChunksMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.lootr.LootrMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.railways.RailwaysMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.tfc.TfcMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.gtceu.GtceuMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.lithium.LithiumMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.modernfix.ModernFixMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.saturn.SaturnMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.naturespirit.NaturesSpiritMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.owo.OwoMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.redirected.RedirectedMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.regionsunexplored.RegionsUnexploredMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.repurposedstructures.RepurposedStructuresMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.spawn.SpawnMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.spectrum.SpectrumMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.tectonic.TectonicMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.terrablender.TerraBlenderMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.variantsandventures.VariantsAndVenturesMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.winterly.WinterlyMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.yungscavebiomes.YungsCaveBiomesMixins;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Other mods' mixins on a hooked vanilla method whose changes GA2 carries into its own fast path. The hook stays on
 * instead of standing down. The code in {@code compat/<modid>} runs the mod's merged handler methods, or does what
 * they do, at the place where vanilla's code would run them. An entry matches a mixin only with exactly the handler
 * methods the entry names. A mod version that changes the method in some other way is not handled, and the hook stands
 * down for that version as for any other mod. The runtime side registers with {@link CompatAdapters}. A handled mixin
 * whose adapter is missing or cannot be set up also keeps the hook off.
 *
 * <p>Loaded by the mixin plugin, so neither this class nor the per-mod tables may reference Minecraft types.
 */
public final class KnownMixins {
    private KnownMixins() {}

    /**
     * @param hook     the {@link HookTargets} key
     * @param mixin    the mixin class
     * @param handlers the handler methods' names as written in the mixin (merged names end with {@code "$" + name})
     * @param what     plain sentences for players: what of the mod still works, and how GA2 keeps it. Shown in the log and
     *                 {@code /ga2 compat report}.
     */
    public record Entry(String modId, String hook, String mixin, Set<String> handlers, String what) {}

    /**
     * Every table's entries, read on first use. The tables call {@link #overwrite} while they initialize, so this class must not
     * read them while it initializes itself.
     */
    private static final class All {
        static final List<Entry> ENTRIES = collect();
    }

    private static List<Entry> collect() {
        List<Entry> all = new ArrayList<>();
        all.addAll(NaturesSpiritMixins.ENTRIES);
        all.addAll(EclipticSeasonsMixins.ENTRIES);
        all.addAll(BiomesWeveGoneMixins.ENTRIES);
        all.addAll(BiolithMixins.ENTRIES);
        all.addAll(RegionsUnexploredMixins.ENTRIES);
        all.addAll(RepurposedStructuresMixins.ENTRIES);
        all.addAll(ModernFixMixins.ENTRIES);
        all.addAll(SaturnMixins.ENTRIES);
        all.addAll(ConfluenceMixins.ENTRIES);
        all.addAll(ChunkLoadMixins.ENTRIES);
        all.addAll(TectonicMixins.ENTRIES);
        all.addAll(OwoMixins.ENTRIES);
        all.addAll(YungsCaveBiomesMixins.ENTRIES);
        all.addAll(BetterCavesMixins.ENTRIES);
        all.addAll(LithiumMixins.ENTRIES);
        all.addAll(SpectrumMixins.ENTRIES);
        all.addAll(VariantsAndVenturesMixins.ENTRIES);
        all.addAll(CobblemonMixins.ENTRIES);
        all.addAll(SpawnMixins.ENTRIES);
        all.addAll(WinterlyMixins.ENTRIES);
        all.addAll(AtmosphericMixins.ENTRIES);
        all.addAll(EnvironmentalMixins.ENTRIES);
        all.addAll(BlockSwapMixins.ENTRIES);
        all.addAll(FormationsMixins.ENTRIES);
        all.addAll(CreateMixins.ENTRIES);
        all.addAll(AlexsCavesMixins.ENTRIES);
        all.addAll(HybridAquaticMixins.ENTRIES);
        all.addAll(VanillaBackportMixins.ENTRIES);
        all.addAll(RedirectedMixins.ENTRIES);
        all.addAll(FabricApiMixins.ENTRIES);
        all.addAll(C2MEMixins.ENTRIES);
        all.addAll(TerraBlenderMixins.ENTRIES);
        all.addAll(BetterChunkLoadingMixins.ENTRIES);
        all.addAll(GtceuMixins.ENTRIES);
        all.addAll(FtbChunksMixins.ENTRIES);
        all.addAll(LootrMixins.ENTRIES);
        all.addAll(RailwaysMixins.ENTRIES);
        all.addAll(AdAstraMixins.ENTRIES);
        all.addAll(DiggerHelmetMixins.ENTRIES);
        all.addAll(TfcMixins.ENTRIES);
        return List.copyOf(all);
    }

    /** MixinExtras routes a handler that takes sugar (@Local, @Share) through a generated bridge named after it. */
    private static final Pattern BRIDGE = Pattern.compile("\\$mixinextras\\$bridge\\$\\d+$");

    /**
     * The entry handling {@code mixin}'s changes to {@code hook}, or null.
     *
     * @param merged names of the merged methods the inspection found (handlers, their bridges, or an overwritten method)
     */
    public static Entry find(String hook, String mixin, Collection<String> merged) {
        for (Entry e : All.ENTRIES) {
            if (!e.hook().equals(hook) || !e.mixin().equals(mixin)) {
                continue;
            }
            boolean all = true;
            for (String name : merged) {
                if (!matches(e, name)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return e;
            }
        }
        return null;
    }

    /** A handler name standing for the mixin's overwrite of the hooked method {@code method} (the merged method keeps its name). */
    public static String overwrite(String method) {
        return "=" + method;
    }

    private static boolean matches(Entry e, String mergedName) {
        String name = BRIDGE.matcher(mergedName).replaceFirst("");
        for (String handler : e.handlers()) {
            if (handler.startsWith("=") ? name.equals(handler.substring(1)) : name.endsWith("$" + handler)) {
                return true;
            }
        }
        return false;
    }
}
