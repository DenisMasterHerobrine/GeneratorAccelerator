package dev.denismasterherobrine.generatoraccelerator2.mixin.storage;

import java.util.List;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** {@code ListTag}'s constructor over a list of elements and their type ({@code storage.PostProcessingTags}). */
@Mixin(ListTag.class)
public interface ListTagInvoker {
    @Invoker("<init>")
    static ListTag ga2$of(List<Tag> elements, byte type) {
        throw new AssertionError();
    }
}
