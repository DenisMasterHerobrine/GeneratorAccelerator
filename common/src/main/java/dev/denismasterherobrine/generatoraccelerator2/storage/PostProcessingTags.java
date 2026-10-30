package dev.denismasterherobrine.generatoraccelerator2.storage;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.mixin.storage.ListTagInvoker;
import it.unimi.dsi.fastutil.shorts.ShortList;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.Tag;

/**
 * {@code ChunkSerializer.packOffsets}: one list tag per section, holding the section's post-processing positions as short tags. Vanilla
 * walks each list with a boxing iterator and adds one short at a time, and each add checks the element type. Here each section's tag
 * is made from its shorts in one go, with the type set once. The tags are equal to vanilla's and write the same bytes. A list another
 * thread may change is packed under its lock by the caller ({@code compat.c2me.PostProcessingLocks}), as before.
 *
 * <p>Off when another mod changes the method (hook {@code storage.postProcessing}), or with
 * {@code -Dgeneratoraccelerator2.postProcessingTags=false}.
 */
public final class PostProcessingTags {
    private PostProcessingTags() {}

    /** Not final so the exactness test can compare both ways. */
    public static boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.postProcessingTags"));
    private static final HookTargets.Hook HOOK = HookTargets.hook(HookTargets.STORAGE_POST_PROCESSING);

    public static boolean enabled() {
        return ENABLED && HOOK.allowed();
    }

    /** {@code packOffsets(lists)}. */
    public static ListTag pack(ShortList[] lists) {
        ListTag out = new ListTag();
        for (ShortList list : lists) {
            out.add(section(list));
        }
        return out;
    }

    /** A section's list: empty (no type) for no list or an empty one, as vanilla's empty tag. */
    private static ListTag section(@Nullable ShortList list) {
        if (list == null || list.isEmpty()) {
            return new ListTag();
        }
        int n = list.size();
        List<Tag> shorts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            shorts.add(ShortTag.valueOf(list.getShort(i)));
        }
        return ListTagInvoker.ga2$of(shorts, Tag.TAG_SHORT);
    }
}
