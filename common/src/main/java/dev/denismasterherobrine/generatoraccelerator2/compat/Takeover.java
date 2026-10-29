package dev.denismasterherobrine.generatoraccelerator2.compat;

import dev.denismasterherobrine.generatoraccelerator2.ConfigFiles;
import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.compat.modernfix.ModernFixMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.yungscavebiomes.YungsCaveBiomesMixins;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;

/**
 * Which mixins of other performance mods GA2 replaces. GA2 takes priority over mods that optimise
 * the same worldgen code (C2ME, ModernFix). Their mixins on the code GA2 owns are cancelled through
 * MixinSquared before they are applied. That code covers noise kernels, density function compilation,
 * aquifer, beardifier, surface rules, carvers and the feature fast paths. GA2's exact paths then run
 * instead of standing down. Everything else those mods do keeps working: chunk systems, lighting,
 * other optimisations and their thread-safety fixes. GA2 adapts to their schedulers instead. Steps run
 * inline on another chunk system's threads.
 *
 * <p>Consulted while mixins are applied, before game classes load, so it must not reference
 * Minecraft types. Turned off by {@code takeOver = false} in
 * {@code config/generatoraccelerator2/compat.toml} or {@code -Dgeneratoraccelerator2.takeOver=false}.
 */
public final class Takeover {
    private Takeover() {}

    private static final Logger LOGGER = Ga2Log.get("Compat");

    /**
     * A cancelled mixin (exact class name, or a package prefix ending in '.') and why GA2 replaces it. {@code when} says whether GA2's
     * replacement is set up. The mixin is only cancelled then.
     */
    private record Rule(String mod, String mixin, String reason, BooleanSupplier when) {
        Rule(String mod, String mixin, String reason) {
            this(mod, mixin, reason, () -> true);
        }

        boolean matches(String className) {
            return mixin.endsWith(".") ? className.startsWith(mixin) : className.equals(mixin);
        }
    }

    private static final List<Rule> RULES = List.of(
        // C2ME
        new Rule("c2me", "com.ishland.c2me.opts.dfc.mixin.", "Replaced by GA2's compiled terrain shape."),
        new Rule("c2me", "com.ishland.c2me.opts.math.mixin.MixinOctavePerlinNoiseSampler", "Replaced by GA2's faster noise math."),
        new Rule("c2me", "com.ishland.c2me.opts.math.mixin.MixinPerlinNoiseSampler", "Replaced by GA2's faster noise math."),
        new Rule("c2me", "com.ishland.c2me.opts.natives_math.mixin.MixinDoublePerlinNoiseSampler", "Replaced by GA2's faster noise math."),
        new Rule("c2me", "com.ishland.c2me.opts.natives_math.mixin.MixinInterpolatedNoiseSampler", "Replaced by GA2's faster noise math."),
        new Rule("c2me", "com.ishland.c2me.opts.natives_math.mixin.MixinDFTypesEndIslands", "Replaced by GA2's compiled terrain shape."),
        new Rule("c2me", "com.ishland.c2me.opts.natives_math.mixin.MixinBiomeAccess", "Replaced by GA2's faster biome lookup."),
        new Rule("c2me", "com.ishland.c2me.opts.natives_math.mixin.MixinMultiNoiseUtilEntries", "Replaced by GA2's exact biome search."),
        // native fillArray of the noise and shift nodes. It casts NormalNoise to the native pointer that C2ME's noise mixin adds.
        // That mixin is cancelled above, so a vanilla NoiseChunk fill (a chunk on the vanilla path) would throw. GA2's compiled
        // programs never call fillArray
        new Rule("c2me", "com.ishland.c2me.opts.natives_math.mixin.df.", "Replaced by GA2's compiled terrain shape."),
        // native tables built in every aquifer's constructor for C2ME's aquifer code. That code is cancelled by the next rule
        new Rule("c2me", "com.ishland.c2me.opts.natives_math.mixin.MixinAquiferSamplerImpl", "Replaced by GA2's faster underground water and lava."),
        new Rule("c2me", "com.ishland.c2me.opts.worldgen.vanilla.mixin.aquifer.MixinAquiferSamplerImpl",
            "Replaced by GA2's faster underground water and lava."),
        new Rule("c2me", "com.ishland.c2me.opts.worldgen.vanilla.mixin.structure_weight_sampler.MixinStructureWeightSampler",
            "Replaced by GA2's faster terrain blending around structures."),
        new Rule("c2me", "com.ishland.c2me.opts.allocs.mixin.surfacebuilder.", "Replaced by GA2's compiled surface rules."),
        new Rule("c2me", "com.ishland.c2me.opts.allocs.mixin.object_pooling_caching.MixinOreFeature", "Replaced by GA2's faster ore placement."),
        // these mixins' test methods would send the predicate programs through the own test of each combined/offset predicate
        new Rule("c2me", "com.ishland.c2me.opts.allocs.mixin.predicates.", "Replaced by GA2's faster placement checks."),
        // this mixin overwrites isShapeFullBlock with a per-thread LRU. The overwrite keeps GA2's flag on the shape out
        new Rule("c2me", "com.ishland.c2me.opts.worldgen.vanilla.mixin.tlcache.MixinBlock", "Replaced by GA2's faster full-block check."),
        new Rule("c2me", "com.ishland.c2me.opts.worldgen.general.mixin.random_instances.MixinRedirectAtomicSimpleRandom",
            "Replaced by GA2's faster caves and geodes."),
        // C2ME's IO rewrite swaps the chunk IOWorker for its own subclass. GA2's region scan, parallel read and
        // region write would never run. C2ME's own config can turn this module off (ioSystem.replaceImpl)
        new Rule("c2me", "com.ishland.c2me.rewrites.chunkio.mixin.", "Replaced by GA2's faster chunk saving and loading."),
        // ModernFix: the mixins of ModernFix's surface rule package work as a set. The context mixin carries the chunk's
        // biomes from the generator's buildSurface to the biome conditions through a thread local. GA2's surface builder
        // would leave that thread local behind, so the whole package goes. ModernFix's other worldgen allocation tweaks
        // stay (vanilla behaviour, untouched hooks)
        new Rule("modernfix", "org.embeddedt.modernfix.common.mixin.perf.optimize_surface_rules.", "Replaced by GA2's compiled surface rules."),
        new Rule("modernfix", "org.embeddedt.modernfix.common.mixin.perf.worldgen_allocation.MaterialRuleListMixin",
            "Replaced by GA2's faster terrain fill."),
        // ModernFix's soft template cache. A later ModernFix also changes the repository's get, which turned GA2's template loads off.
        // GA2 makes the same soft cache itself (structure.TemplateLoads), so templates still leave memory as with ModernFix
        new Rule("modernfix", ModernFixMixins.DYNAMIC_STRUCTURES,
            "Replaced by GA2's template loads. Loaded templates are still kept the soft way ModernFix keeps them, so memory use stays the same."),
        new Rule("modernfix", "org.embeddedt.modernfix.common.mixin.perf.worldgen_allocation.ClimateParameterListMixin",
            "Replaced by GA2's exact biome search, which uses the biome tree this tweak delays."),
        // Lithium: only Lithium's section packing goes. The packing reuses the order of a hash palette, so the saved bytes
        // are not vanilla's. Lithium's lock removal, palettes and the rest stay
        new Rule("lithium", "net.caffeinemc.mods.lithium.mixin.chunk.serialization.PalettedContainerMixin",
            "Replaced by GA2's exact chunk saving, which keeps saved data identical to vanilla."),
        // Moonrise (not tested): only the biome zoom. GA2 keeps Moonrise's chunk system
        new Rule("moonrise", "ca.spottedleaf.moonrise.mixin.random_ticking.BiomeManagerMixin", "Replaced by GA2's faster biome lookup."),
        // zfastnoise: zfastnoise's noise fill replaces the generator's fill task with its own loop over the NoiseChunk. GA2's
        // fill (FastFill with its section writes and compiled cells) therefore never ran. zfastnoise's biome fill touches
        // nothing of GA2's and stays as is.
        new Rule("zfastnoise", "org.codeberg.zenxarch.fastnoise.mixin.perf.noise.", "Replaced by GA2's faster terrain fill."),
        // Noisium (NoisiumForked): Noisium's generator mixin replaces the fill's section writes, and GA2's fill and GPU noise hooks
        // stood down for it. The results are vanilla's either way. Noisium's material rule, biome section fill and cell size caches
        // also give vanilla's results. All of Noisium's mixins go
        new Rule("noisium", "io.github.steveplays28.noisium.mixin.", "Replaced by GA2's faster terrain fill and GPU terrain."),
        // GA2 does the same step itself (compat.yungscavebiomes.FrostedCaves)
        new Rule("yungscavebiomes", YungsCaveBiomesMixins.AQUIFER,
            "Replaced by GA2's own Frosted Caves water, so the faster underground water and lava keep running.", YungsCaveBiomesMixins::frostedReplaced));

    private static final Map<String, String> CANCELLED = new ConcurrentHashMap<>();
    private static volatile Boolean enabled;
    /** Set once MixinSquared asks about any mixin. Without MixinSquared nothing is taken over. */
    private static volatile boolean consulted;
    private static volatile boolean consultChecked;

    /**
     * Whether GA2 cancels {@code mixinClassName} when its target class loads. Mixin asks {@link #shouldCancel} only then, often well
     * into worldgen, so code that replaces a cancelled mixin's work asks this at mod construction instead.
     */
    public static boolean willCancel(String mixinClassName) {
        if (!enabled()) {
            return false;
        }
        for (Rule rule : RULES) {
            if (rule.matches(mixinClassName)) {
                return true;
            }
        }
        return false;
    }

    /** Called by MixinSquared for every mixin of every config. */
    public static boolean shouldCancel(List<String> targets, String mixinClassName) {
        consulted = true;
        if (mixinClassName.startsWith("dev.denismasterherobrine.generatoraccelerator2.")) {
            return false;
        }
        if (!enabled()) {
            return false;
        }
        for (Rule rule : RULES) {
            if (rule.matches(mixinClassName) && rule.when().getAsBoolean()) {
                if (CANCELLED.putIfAbsent(mixinClassName, rule.mod + ": " + rule.reason) == null) {
                    LOGGER.info("takeover {}: cancelled {} on {} ({})", rule.mod, mixinClassName, targets, rule.reason);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Warns once if MixinSquared never asked about a mixin. MixinSquared asks when a mixin's target class loads. By the time a server
     * starts, many targets of GA2's own mixins have loaded. A jar built without the bundled MixinSquared shows up here.
     */
    public static void checkConsulted() {
        if (consultChecked) {
            return;
        }
        consultChecked = true;
        if (enabled() && !consulted) {
            LOGGER.warn("MixinSquared never consulted -> no takeover, other mods' mixins on GA2's code stay and GA2 hooks may stand down");
        }
    }

    /** Cancelled mixin class name to "mod: reason", for {@code /ga2 compat report}. */
    public static Map<String, String> cancelled() {
        return Collections.unmodifiableMap(CANCELLED);
    }

    public static boolean enabled() {
        Boolean e = enabled;
        if (e == null) {
            e = readSwitch("takeOver", "generatoraccelerator2.takeOver");
            enabled = e;
            if (!e) {
                LOGGER.warn("takeover off -> foreign worldgen mixins kept, GA2 hooks may stand down");
            }
        }
        return e;
    }

    /** The config is not loaded yet when mixins apply, so the switch is read from the file directly (default: on). */
    private static boolean readSwitch(String key, String systemProperty) {
        String property = System.getProperty(systemProperty);
        if (property != null) {
            return Boolean.parseBoolean(property);
        }
        try {
            // the folder's file, or an older version's file not moved yet. The move happens when the mod is constructed, after mixins
            Path file = ConfigFiles.existing("compat");
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file)) {
                    String l = line.trim();
                    int eq = l.indexOf('=');
                    if (eq > 0 && l.substring(0, eq).trim().equals(key)) {
                        return !l.substring(eq + 1).trim().startsWith("false");
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("compat config: read of {} failed -> on", key, e);
        }
        return true;
    }
}
