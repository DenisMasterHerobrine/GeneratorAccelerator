package dev.denismasterherobrine.generatoraccelerator2.mixin.state;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.state.DirectSets;
import dev.denismasterherobrine.generatoraccelerator2.state.TagBits;
import java.util.Collection;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code Holder.Reference.is(TagKey)} by bit ({@link TagBits}). Covers every registry's tag checks, in vanilla's and every
 * mod's code. Put in place once the class is complete, when no other mod changed {@code is} ({@code compat.LateOverwrites}).
 * Another mod's change to these methods is recorded ({@code tags.bits}). In that case the tag set answers.
 */
@Mixin(value = Holder.Reference.class, priority = 5000)
public abstract class HolderTagsMixin<T> implements DirectSets.Keyed {
    @Shadow
    private Set<TagKey<T>> tags;

    @Shadow
    private ResourceKey<T> key;

    @Unique
    private long[] ga2$tagBits = TagBits.NONE;

    @Override
    public ResourceKey<?> ga2$keyOrNull() {
        return this.key;
    }

    @Unique
    private static final HookTargets.Hook GA2$HOOK = HookTargets.hook(HookTargets.TAG_BITS);

    @Inject(method = "bindTags", at = @At("TAIL"))
    private void ga2$bindBits(Collection<TagKey<T>> tags, CallbackInfo ci) {
        this.ga2$tagBits = TagBits.bits(this.tags);
    }

    /** The body of {@code is(tag)}. Reads the holder's tag bits instead of hashing the key into the tag set. Same answer (see {@link TagBits}). */
    @Unique
    private boolean ga2$is(TagKey<T> tag) {
        if (!(dev.denismasterherobrine.generatoraccelerator2.state.StateFast.ENABLED && GA2$HOOK.allowed())) {
            return this.tags.contains(tag);
        }
        int i = TagBits.index(tag);
        if (i < 0) {
            // no holder was bound with this key, so no holder's set holds it (TagBits)
            return false;
        }
        long[] b = this.ga2$tagBits;
        int w = i >>> 6;
        return w < b.length && (b[w] & (1L << i)) != 0;
    }
}
