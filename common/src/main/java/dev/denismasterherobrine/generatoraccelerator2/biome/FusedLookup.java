package dev.denismasterherobrine.generatoraccelerator2.biome;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.terrablender.TerraBlenderMixins;
import dev.denismasterherobrine.generatoraccelerator2.dfc.Compat;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.BoundSampler;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.DfcHooks;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.SamplerWrappers;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code MultiNoiseBiomeSource.getNoiseBiome(x, y, z, sampler)} in one step, for a sampler bound to the compiled climate program.
 * The program's six values are quantized as {@code Climate.target} quantizes them. Then the flat search {@code findValueIndex} runs
 * ({@link ClimateSearch}). No target point is built, and neither is the callback object of the sampling context or of the search.
 * The values and the search are the same as in the two calls this replaces. The biome and the thread's last result are therefore
 * the same. Applies only to vanilla's biome source and parameter list classes, and only while no other mod changes the lookup.
 * The {@code gpu.biomes} hook covers the two {@code getNoiseBiome}, {@code parameters} and {@code Climate.Sampler.sample}. The
 * {@code biomes.climateSearch} hook covers the searches of the parameter list.
 */
public final class FusedLookup {
    private FusedLookup() {}

    /** {@code -Dgeneratoraccelerator2.fusedBiomeLookup=false} keeps the sample and the search apart. */
    private static final boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.fusedBiomeLookup"));
    private static final HookTargets.Hook LOOKUP = HookTargets.hook(HookTargets.GPU_BIOMES);
    private static final int VERIFY_EVERY = Integer.getInteger("generatoraccelerator2.verify.biomes", 0);
    private static final AtomicLong SEEN = new AtomicLong();
    /** Fused lookups also made the two-call way and compared ({@code verify.biomes}), and the lookups that differed. */
    public static final AtomicLong VERIFIED = new AtomicLong(), DIFFERING = new AtomicLong();

    /**
     * Whether a lookup may be fused at all. If so, the source's {@code parameters()} is vanilla's, which only reads.
     *
     * @param sourceIsVanilla whether the biome source is {@code MultiNoiseBiomeSource} itself (not a subclass)
     */
    public static boolean ready(boolean sourceIsVanilla) {
        return ENABLED && !TERRABLENDER && sourceIsVanilla && DfcHooks.enabled() && LOOKUP.allowed() && ClimateSearch.enabled();
    }

    /**
     * TerraBlender's handled mixins allow {@code gpu.biomes}, but its lookup searches a region's tree. This lookup searches the list's
     * own tree, so it stays off with TerraBlender.
     */
    private static final boolean TERRABLENDER = Compat.isLoaded(
        TerraBlenderMixins.MOD_ID);

    /**
     * Returns the biome of the quart, or null when this class cannot make the lookup. On null, the caller samples and searches as
     * vanilla does. Call only after {@link #ready}.
     */
    @Nullable
    public static Holder<Biome> lookup(Climate.ParameterList<Holder<Biome>> parameters, int x, int y, int z, Climate.Sampler sampler) {
        if (parameters.getClass() != Climate.ParameterList.class) {
            return null;
        }
        Climate.RTree<Holder<Biome>> index = parameters.index;
        if (index == null) {
            // Another mod builds the tree lazily inside the two-argument search (e.g. ModernFix's deferred index).
            return null;
        }
        BoundSampler bound = SamplerWrappers.resolve(sampler);
        if (bound == null) {
            return null;
        }
        double[] v = bound.compute(x, y, z);
        Holder<Biome> biome = ClimateSearch.search(index, Climate.quantizeCoord((float) v[0]), Climate.quantizeCoord((float) v[1]),
            Climate.quantizeCoord((float) v[2]), Climate.quantizeCoord((float) v[3]), Climate.quantizeCoord((float) v[4]), Climate.quantizeCoord((float) v[5]));
        if (VERIFY_EVERY > 0 && SEEN.incrementAndGet() % VERIFY_EVERY == 0) {
            // -Dgeneratoraccelerator2.verify.biomes=N repeats the two calls. Same leaf, so the thread's last result is unchanged.
            Holder<Biome> expected = parameters.findValue(sampler.sample(x, y, z));
            VERIFIED.incrementAndGet();
            if (expected != biome && DIFFERING.incrementAndGet() <= 10) {
                Ga2Log.get("Verify").warn("fused biome lookup mismatch at {},{},{}: got {}, expected {}",
                    x, y, z, biome, expected);
            }
        }
        return biome;
    }
}
