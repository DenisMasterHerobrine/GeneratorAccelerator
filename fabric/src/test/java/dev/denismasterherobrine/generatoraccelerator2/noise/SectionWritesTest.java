package dev.denismasterherobrine.generatoraccelerator2.noise;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.chunkio.SectionWriter;
import dev.denismasterherobrine.generatoraccelerator2.chunkio.SectionWrites;
import dev.denismasterherobrine.generatoraccelerator2.features.verify.FeatureCompare;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.util.List;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.junit.jupiter.api.Test;

/**
 * A known change to section writes that acts on some states ({@link SectionWrites}) sends the sections holding them through
 * {@code setBlockState}. The fill then leaves the same blocks, counters and heightmaps as without it.
 */
class SectionWritesTest {
    @Test
    void actedOnStatesGoThroughSetBlockState() {
        TerrainHarness.World w = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L);
        long packed = SectionWriter.SECTIONS.sum();
        ProtoChunk direct = w.noise(3, -7);
        assertTrue(SectionWriter.SECTIONS.sum() > packed, "the fill did not pack its sections");
        long replayed = SectionWriter.REPLAYED.sum();
        Runnable off = SectionWrites.register(s -> s.is(Blocks.STONE));
        ProtoChunk filtered;
        try {
            filtered = w.noise(3, -7);
        } finally {
            off.run();
        }
        assertTrue(SectionWriter.REPLAYED.sum() > replayed, "no section with stone went through setBlockState");
        List<String> diff = FeatureCompare.compare(direct, filtered, 12);
        assertTrue(diff.isEmpty(), String.join("\n", diff));
    }
}
