package dev.denismasterherobrine.generatoraccelerator2.mixin.structure;

import java.util.List;
import dev.denismasterherobrine.generatoraccelerator2.structure.JigsawBlocks;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Jigsaw assembly sorts the jigsaw blocks of every template by {@code selection_priority}. Vanilla reads
 * the priority from the block NBT on every comparison. The sort is stable, so it changes nothing when every
 * block has the same priority. That case is detected with one read per block and skipped. Otherwise the
 * same stable sort runs on priorities read once per block: highest priority first, ties kept in order.
 */
@Mixin(SinglePoolElement.class)
public abstract class SinglePoolElementMixin {
    @Inject(method = "sortBySelectionPriority", at = @At("HEAD"), cancellable = true)
    private static void ga2$skipEqualPriorities(List<StructureTemplate.StructureBlockInfo> blocks, CallbackInfo ci) {
        int n = blocks.size();
        if (n < 2) {
            ci.cancel();
            return;
        }
        int[] priority = new int[n];
        boolean equal = true;
        for (int i = 0; i < n; i++) {
            priority[i] = ga2$priority(blocks.get(i));
            equal &= priority[i] == priority[0];
        }
        if (!equal) {
            if (!JigsawBlocks.ENABLED) {
                return; // vanilla's sort, as before
            }
            // stable insertion sort, descending: an entry only moves past strictly lower priorities
            StructureTemplate.StructureBlockInfo[] sorted = blocks.toArray(new StructureTemplate.StructureBlockInfo[0]);
            for (int i = 1; i < n; i++) {
                StructureTemplate.StructureBlockInfo block = sorted[i];
                int p = priority[i];
                int j = i - 1;
                while (j >= 0 && priority[j] < p) {
                    sorted[j + 1] = sorted[j];
                    priority[j + 1] = priority[j];
                    j--;
                }
                sorted[j + 1] = block;
                priority[j + 1] = p;
            }
            for (int i = 0; i < n; i++) {
                blocks.set(i, sorted[i]);
            }
        }
        ci.cancel();
    }

    @Unique
    private static int ga2$priority(StructureTemplate.StructureBlockInfo block) {
        CompoundTag nbt = block.nbt();
        return nbt == null ? 0 : nbt.getInt("selection_priority");
    }
}
