package dev.denismasterherobrine.generatoraccelerator2.mixin.state;

import dev.denismasterherobrine.generatoraccelerator2.state.TagBits;
import net.minecraft.tags.TagKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Keeps the tag key's bit index ({@link TagBits}) on the interned key itself. A field read replaces a map lookup. */
@Mixin(TagKey.class)
public abstract class TagKeyIndexMixin implements TagBits.Indexed {
    /** The index + 1 once given, 0 before. */
    @Unique
    private volatile int ga2$tagIndex;

    @Override
    public int ga2$tagIndex() {
        return ga2$tagIndex;
    }

    @Override
    public void ga2$setTagIndex(int index) {
        this.ga2$tagIndex = index;
    }
}
