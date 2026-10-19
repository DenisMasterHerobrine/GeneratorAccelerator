package dev.denismasterherobrine.generatoraccelerator2.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.structures.NetherFossilStructure;
import org.junit.jupiter.api.Test;

/**
 * The fossil probe's early answer is only ever "empty where vanilla's placement is empty", for biome checks that accept
 * nothing in the overworld (the fossil's own), everything, and about half of all biomes. Replaying the random leaves
 * the context's own random untouched.
 */
public class FossilProbeTest {
    @Test
    void skipsOnlyWhatVanillaRejects() {
        TerrainHarness.World world = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 777L);
        NetherFossilStructure structure = (NetherFossilStructure) world.registries.lookup.lookupOrThrow(Registries.STRUCTURE)
            .getOrThrow(BuiltinStructures.NETHER_FOSSIL).value();
        Predicate<Holder<Biome>> own = structure.biomes()::contains;
        Predicate<Holder<Biome>> any = holder -> true;
        // about half of all biomes, so that skipped and probed chunks both occur
        Predicate<Holder<Biome>> some = holder -> (holder.getRegisteredName().hashCode() & 1) == 0;
        int skipped = 0;
        int probed = 0;
        int present = 0;
        int someSkipped = 0;
        for (int cx = -9; cx <= 9; cx += 3) {
            for (int cz = -9; cz <= 9; cz += 3) {
                for (Predicate<Holder<Biome>> valid : List.of(own, any, some)) {
                    Structure.GenerationContext context = context(world, structure, cx, cz, valid);
                    boolean skip = FossilProbe.skip(structure, structure.height, context);
                    Optional<Structure.GenerationStub> vanilla;
                    FossilProbe.ENABLED = false;
                    try {
                        vanilla = structure.findValidGenerationPoint(context);
                        // a fresh context gives the same answer: the probe's replay did not advance the context's random
                        Optional<Structure.GenerationStub> fresh = structure.findValidGenerationPoint(context(world, structure, cx, cz, valid));
                        assertEquals(fresh.map(Structure.GenerationStub::position), vanilla.map(Structure.GenerationStub::position), "random untouched at " + cx + "," + cz);
                    } finally {
                        FossilProbe.ENABLED = true;
                    }
                    if (skip) {
                        assertTrue(vanilla.isEmpty(), "skipped a placement vanilla makes at " + cx + "," + cz + ": " + vanilla.map(Structure.GenerationStub::position));
                        skipped++;
                        if (valid == some) {
                            someSkipped++;
                        }
                    } else {
                        probed++;
                        if (vanilla.isPresent()) {
                            present++;
                        }
                    }
                }
            }
        }
        System.out.println("FossilProbeTest: " + skipped + " probes skipped (" + someSkipped + " of the half-biome check), " + probed + " run (" + present + " placements)");
        assertTrue(skipped > 0, "some probes skipped");
        assertTrue(present > 0, "some placements found");
    }

    private static Structure.GenerationContext context(TerrainHarness.World world, Structure structure, int cx, int cz, Predicate<Holder<Biome>> valid) {
        return new Structure.GenerationContext(world.registries.access, world.generator, world.biomeSource, world.randomState, null, world.seed,
            new ChunkPos(cx, cz), world.heights, valid);
    }
}
