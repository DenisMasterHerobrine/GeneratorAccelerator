package dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.features.HarnessWorld;
import dev.worldgen.lithostitched.impl.worldgen.biomeinjector.ReplacePartially;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.biome.Biome;
import org.junit.jupiter.api.Test;

/**
 * {@link LithostitchedInjectors}: walking the per-biome subset of the partial replacements finds the same first match as
 * walking all of them, for lists with targets of one to a dozen biomes, biomes no replacement targets, and parameters
 * that sometimes say no.
 */
public class InjectorIndexTest {
    @Test
    void subsetWalkFindsTheSameReplacement() {
        List<Holder.Reference<Biome>> biomes = HarnessWorld.registries().biomes.holders().toList();
        Random r = new Random(11);
        for (int round = 0; round < 20; round++) {
            List<ReplacePartially> injectors = new ArrayList<>();
            int count = 4 + r.nextInt(200);
            for (int i = 0; i < count; i++) {
                List<Holder<Biome>> targets = new ArrayList<>();
                int n = 1 + r.nextInt(r.nextInt(5) == 0 ? 12 : 2);
                for (int k = 0; k < n; k++) {
                    targets.add(biomes.get(r.nextInt(biomes.size())));
                }
                int salt = r.nextInt();
                injectors.add(new ReplacePartially(HolderSet.direct(targets), biomes.get(r.nextInt(biomes.size())),
                    b -> ((b.hashCode() * 31 + salt) & 3) != 0));
            }
            for (Holder<Biome> biome : biomes) {
                Holder<Biome> expected = biome;
                for (ReplacePartially p : injectors) {
                    if (p.matches(biome)) {
                        expected = p.replacement();
                        break;
                    }
                }
                LithostitchedInjectors.noteBiome(biome);
                @SuppressWarnings("unchecked")
                List<ReplacePartially> walk = (List<ReplacePartially>) LithostitchedInjectors.candidates(ReplacePartially.CODEC, injectors);
                assertTrue(walk.size() <= injectors.size());
                Holder<Biome> actual = biome;
                for (ReplacePartially p : walk) {
                    if (p.matches(biome)) {
                        actual = p.replacement();
                        break;
                    }
                }
                assertSame(expected, actual, "round " + round + ", biome " + biome.getRegisteredName());
            }
        }
        assertTrue(LithostitchedInjectors.skipped() > 0, "the subsets skipped replacements");
    }

    @Test
    void otherInjectorTypesAreWalkedWhole() {
        List<Object> list = List.of(new Object(), new Object(), new Object(), new Object(), new Object());
        Holder<Biome> biome = HarnessWorld.registries().biomes.holders().findFirst().orElseThrow();
        LithostitchedInjectors.noteBiome(biome);
        assertSame(list, LithostitchedInjectors.candidates(new Object(), list));
        assertSame(list, LithostitchedInjectors.candidates(ReplacePartially.CODEC, list));
        // again: the list's kept index has no targets and must keep the whole walk (it was handed out as an index before)
        assertSame(list, LithostitchedInjectors.candidates(ReplacePartially.CODEC, list));
    }
}
