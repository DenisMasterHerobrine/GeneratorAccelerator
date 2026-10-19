package dev.denismasterherobrine.generatoraccelerator2.structure;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.mixin.structure.StructureInvoker;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.structures.NetherFossilStructure;

/**
 * Skips the column probe of a nether fossil whose spot can have no valid biome.
 *
 * <p>{@code NetherFossilStructure.findGenerationPoint} draws a column of the chunk and a start height from the context's
 * random. It then asks the generator for the whole noise column, which builds a NoiseChunk of its own and runs every
 * block through the material rules. It walks down from the start height to the first block under air. The biome of
 * that spot is checked only afterwards. The structure is tried in three chunks of four. A mod that adds an overworld
 * biome to the fossil's biome tag makes every overworld chunk run that probe. The biome check then rejects the result
 * nearly everywhere.
 *
 * <p>The draws are replayed on a copy of the context's random. The probe itself does not touch that copy. The replay
 * gives the column and the heights the walk can stop at: above the sea level and below the start. When no quart level
 * in that range holds a biome the check accepts, the result is empty whatever the column holds, and the probe is
 * skipped. The biome check is vanilla's own ({@code Structure.isValidBiome}), applied to a stub at each candidate level.
 */
public final class FossilProbe {
    private FossilProbe() {}

    /** Not final so the exactness test can compare both ways. */
    public static boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.fossilProbe"));
    public static final LongAdder PROBES = new LongAdder();
    public static final LongAdder SKIPPED = new LongAdder();

    private static final HookTargets.Hook HOOK = HookTargets.hook(HookTargets.STRUCTURE_FOSSIL_PROBE);

    /** True when {@code findGenerationPoint} would return empty, i.e. no level the probe can stop at has a valid biome. */
    public static boolean skip(Structure structure, HeightProvider height, Structure.GenerationContext context) {
        if (!ENABLED || structure.getClass() != NetherFossilStructure.class || !HOOK.allowed()) {
            return false;
        }
        WorldgenRandom random = copy(context.random());
        if (random == null) {
            return false;
        }
        PROBES.increment();
        ChunkPos chunkPos = context.chunkPos();
        int x = chunkPos.getMinBlockX() + random.nextInt(16);
        int z = chunkPos.getMinBlockZ() + random.nextInt(16);
        int seaLevel = context.chunkGenerator().getSeaLevel();
        int start = height.sample(random, new WorldGenerationContext(context.chunkGenerator(), context.heightAccessor()));
        // Vanilla walks down from the start while above the sea level and stops on the block below air. At or below the
        // sea level it gives up. A found spot therefore lies in [seaLevel + 1, start - 1].
        int lowest = seaLevel + 1;
        int highest = start - 1;
        if (highest >= lowest) {
            int lastQuart = Integer.MIN_VALUE;
            for (int y = lowest; y <= highest; y++) {
                int quart = y >> 2;
                if (quart == lastQuart) {
                    continue; // The biome is looked up per quart.
                }
                lastQuart = quart;
                Structure.GenerationStub stub = new Structure.GenerationStub(new BlockPos(x, y, z), builder -> {});
                if (StructureInvoker.ga2$isValidBiome(stub, context)) {
                    return false;
                }
            }
        }
        SKIPPED.increment();
        return true;
    }

    /** A generator in the same state as {@code random}, or null when it is not vanilla's legacy generator in a plain state. */
    @Nullable
    static WorldgenRandom copy(WorldgenRandom random) {
        if (random.getClass() != WorldgenRandom.class || !(random.randomSource instanceof LegacyRandomSource legacy) || legacy.getClass() != LegacyRandomSource.class
            || legacy.gaussianSource.haveNextNextGaussian) {
            return null;
        }
        LegacyRandomSource source = new LegacyRandomSource(0L);
        source.seed.set(legacy.seed.get());
        return new WorldgenRandom(source);
    }
}
