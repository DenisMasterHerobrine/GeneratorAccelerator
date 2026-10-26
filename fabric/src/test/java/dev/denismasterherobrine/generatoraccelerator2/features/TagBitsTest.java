package dev.denismasterherobrine.generatoraccelerator2.features;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.state.TagBits;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import org.junit.jupiter.api.Test;

/**
 * {@code Holder.Reference.is(TagKey)} by bits ({@code state.TagBits}) against the holder's own tag set, for every block and every
 * block tag, with the interned keys and with equal keys made without interning.
 */
public class TagBitsTest {
    @Test
    void sameAsTagSets() {
        HarnessWorld.registries();
        List<Holder.Reference<Block>> blocks = BuiltInRegistries.BLOCK.holders().toList();
        Set<TagKey<Block>> tags = new HashSet<>();
        for (Holder.Reference<Block> b : blocks) {
            b.tags().forEach(tags::add);
        }
        assertTrue(tags.size() > 100, "too few block tags bound: " + tags.size());
        List<String> wrong = new ArrayList<>();
        long checked = 0;
        long members = 0;
        for (TagKey<Block> tag : tags) {
            @SuppressWarnings("unchecked")
            TagKey<Block> copy = new TagKey<>(Registries.BLOCK, tag.location());
            for (Holder.Reference<Block> b : blocks) {
                boolean truth = b.tags().anyMatch(tag::equals);
                if (b.is(tag) != truth) {
                    wrong.add(b.getRegisteredName() + " " + tag.location() + ": " + !truth + " (set " + truth + ")");
                }
                if (b.is(copy) != truth) {
                    wrong.add(b.getRegisteredName() + " " + tag.location() + " (copy): " + !truth + " (set " + truth + ")");
                }
                checked += 2;
                if (truth) {
                    members++;
                }
            }
        }
        System.out.println("TagBitsTest: " + checked + " checks over " + tags.size() + " tags, " + members + " memberships");
        assertTrue(wrong.isEmpty(), wrong.size() + " wrong:\n" + String.join("\n", wrong.subList(0, Math.min(20, wrong.size()))));
    }

    /** A key no holder was bound with has no index. Once a holder is bound with it, the same key instance finds its index. */
    @Test
    void absentKeyFindsItsIndexLater() {
        HarnessWorld.registries();
        TagKey<Block> key = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("ga2test", "late_tag"));
        assertEquals(-1, TagBits.index(key));
        assertEquals(-1, TagBits.index(key));
        TagBits.bits(Set.of(key));
        int i = TagBits.index(key);
        assertTrue(i >= 0, "no index after binding: " + i);
        @SuppressWarnings("unchecked")
        TagKey<Block> copy = new TagKey<>(Registries.BLOCK, key.location());
        assertEquals(i, TagBits.index(copy));
    }
}
