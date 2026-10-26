package dev.denismasterherobrine.generatoraccelerator2.mixin.state;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.state.DirectSets;
import java.util.List;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * {@code HolderSet.Direct.contains} by bit ({@link DirectSets}) for reference holders. Put in place once the class is complete,
 * when no other mod changed {@code contains} ({@code compat.LateOverwrites}). Another mod's change to it or to how reference
 * holders compare is recorded ({@code tags.directSets}). In that case the set answers.
 */
@Mixin(value = HolderSet.Direct.class, priority = 5000)
public abstract class DirectSetMixin<T> {
    @Shadow
    @Final
    private List<Holder<T>> contents;

    @Shadow
    private Set<Holder<T>> contentsSet;

    /** The bits of the entries' keys, made at the first test of a reference holder. */
    @Unique
    private volatile long[] ga2$bits;

    @Unique
    private static final HookTargets.Hook GA2$HOOK = HookTargets.hook(HookTargets.DIRECT_SETS);

    /** The body of {@code contains(holder)}. Same answer as vanilla's (see {@link DirectSets}). */
    @Unique
    private boolean ga2$contains(Holder<T> holder) {
        if (DirectSets.MODE != DirectSets.OFF && holder != null && holder.getClass() == Holder.Reference.class && GA2$HOOK.allowed()) {
            ResourceKey<?> key = ((DirectSets.Keyed) holder).ga2$keyOrNull();
            if (key != null) {
                long[] bits = this.ga2$bits;
                if (bits == null) {
                    this.ga2$bits = bits = DirectSets.bits(this.contents);
                }
                if (bits != DirectSets.VANILLA) {
                    int a = DirectSets.answer(DirectSets.MODE, bits, key);
                    if (a != DirectSets.ASK) {
                        return a == DirectSets.YES;
                    }
                }
            }
        }
        if (this.contentsSet == null) {
            this.contentsSet = Set.copyOf(this.contents);
        }
        return this.contentsSet.contains(holder);
    }
}
