package dev.denismasterherobrine.generatoraccelerator2.features;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.state.DirectSets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;

/**
 * {@code HolderSet.Direct.contains} by bits ({@code state.DirectSets}) against membership by vanilla's comparison, for random
 * block lists and every block. Vanilla's reference holders compare by identity, so a holder of the same key that is another
 * object is not a member. NeoForge's compare by key, which the bits answer for both.
 */
public class DirectSetsTest {
    @Test
    void sameAsVanillaMembership() {
        HarnessWorld.registries();
        assertEquals(DirectSets.IDENTITY, DirectSets.MODE, "Fabric's reference holders compare by identity");
        List<Holder.Reference<Block>> blocks = BuiltInRegistries.BLOCK.listElements().toList();
        Random r = new Random(7);
        List<String> wrong = new ArrayList<>();
        long checked = 0;
        for (int n : new int[] { 0, 1, 2, 5, 9, 40, 300 }) {
            for (int round = 0; round < 4; round++) {
                List<Holder<Block>> list = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    list.add(blocks.get(r.nextInt(blocks.size())));
                }
                HolderSet<Block> set = HolderSet.direct(list);
                for (Holder.Reference<Block> b : blocks) {
                    boolean truth = list.stream().anyMatch(h -> h == b);
                    if (set.contains(b) != truth) {
                        wrong.add(n + " entries, " + b.getRegisteredName() + ": " + !truth);
                    }
                    checked++;
                }
                // the same key in another holder object: not the same holder for vanilla
                if (!list.isEmpty()) {
                    Holder.Reference<Block> member = (Holder.Reference<Block>) list.get(0);
                    Holder.Reference<Block> other = Holder.Reference.createStandAlone(BuiltInRegistries.BLOCK, member.key());
                    if (set.contains(other)) {
                        wrong.add(n + " entries, stand-alone " + member.getRegisteredName() + ": true");
                    }
                }
            }
        }
        System.out.println("DirectSetsTest: " + checked + " checks");
        assertTrue(wrong.isEmpty(), wrong.size() + " wrong:\n" + String.join("\n", wrong.subList(0, Math.min(20, wrong.size()))));
    }

    @Test
    void bitsAnswerByKey() {
        HarnessWorld.registries();
        List<Holder.Reference<Block>> blocks = BuiltInRegistries.BLOCK.listElements().toList();
        List<Holder<Block>> list = List.of(blocks.get(3), blocks.get(10), blocks.get(200));
        long[] bits = DirectSets.bits(list);
        for (Holder.Reference<Block> b : blocks) {
            boolean member = list.contains(b);
            assertEquals(member ? DirectSets.YES : DirectSets.NO, DirectSets.answer(DirectSets.KEY, bits, b.key()), b.getRegisteredName());
            assertEquals(member ? DirectSets.ASK : DirectSets.NO, DirectSets.answer(DirectSets.IDENTITY, bits, b.key()), b.getRegisteredName());
        }
        // a holder that is not a reference: the set answers
        assertSame(DirectSets.VANILLA, DirectSets.bits(List.of(blocks.get(3), Holder.direct(blocks.get(4).value()))));
    }

    /** The overwrite is in: a set keeps its bits after the first test of a reference holder. */
    @Test
    void containsIsGa2s() throws Exception {
        HarnessWorld.registries();
        Holder.Reference<Block> stone = BuiltInRegistries.BLOCK.listElements().toList().get(1);
        HolderSet<Block> set = HolderSet.direct(List.of(stone));
        Field bits = HolderSet.Direct.class.getDeclaredField("ga2$bits");
        bits.setAccessible(true);
        assertTrue(set.contains(stone));
        assertTrue(bits.get(set) != null, "bits kept");
    }
}
