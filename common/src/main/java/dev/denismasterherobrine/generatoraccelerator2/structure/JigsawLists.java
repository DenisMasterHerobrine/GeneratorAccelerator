package dev.denismasterherobrine.generatoraccelerator2.structure;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

/**
 * A jigsaw piece's jigsaw blocks at position zero, kept per template and rotation. The jigsaw assembly asks every candidate piece
 * for them in each rotation it tries, for every jigsaw it tries to attach to ({@code SinglePoolElement.getShuffledJigsawBlocks}
 * with {@code BlockPos.ZERO}). Vanilla reads a palette's jigsaws once, but {@code StructureTemplate.getJigsaws} still makes a new
 * list on every call: new place settings, a new random to pick the palette, and per jigsaw a moved position, a rotated state and
 * two new records. A large jigsaw structure asks millions of times, nearly all of them for a piece and rotation asked before.
 *
 * <p>At position zero the list depends only on the template and the rotation. The palette is picked by a random seeded from the
 * position, not by the assembly's random. Every caller gets a new {@code ArrayList} of the kept elements. Vanilla shuffles and sorts
 * that list with the assembly's random as before, so the pieces and the random draws are the same. The elements are records.
 * Vanilla's new elements share the palette's NBT objects too. The kept lists are dropped whenever the template's palettes are made
 * again ({@code load}, {@code fillFromWorld}).
 *
 * <p>Only while no other mod changes the call or {@code getJigsaws} (hook {@code structures.jigsawLists}).
 * {@code -Dgeneratoraccelerator2.jigsawLists=false} turns it off.
 */
public final class JigsawLists {
    private JigsawLists() {}

    public static final boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.jigsawLists"));
    private static final HookTargets.Hook HOOK = HookTargets.hook(HookTargets.STRUCTURE_JIGSAW_LISTS);
    private static final int ROTATIONS = Rotation.values().length;

    /**
     * {@code -Dgeneratoraccelerator2.verify.jigsaws=N}: every N-th kept list is compared with a list made by vanilla. The made
     * list is handed out then.
     */
    private static final int VERIFY_EVERY = Integer.getInteger("generatoraccelerator2.verify.jigsaws", 0);
    private static final AtomicLong VERIFY_COUNTER = new AtomicLong();
    public static final LongAdder VERIFIED = new LongAdder(), DIFFERING = new LongAdder();
    private static final Logger LOGGER = Ga2Log.get("Verify");

    /** Lists handed out from the kept ones, and lists made by vanilla to keep. */
    public static final LongAdder KEPT = new LongAdder(), MADE = new LongAdder();

    /** The kept lists of a template, one slot per rotation ({@code mixin.structure.StructureTemplateJigsawsMixin}). */
    public interface Template {
        List<?>[] ga2$jigsawLists();

        void ga2$jigsawLists(List<?>[] lists);
    }

    /** {@code template.getJigsaws(pos, rotation)} in {@code getShuffledJigsawBlocks}. */
    @SuppressWarnings("unchecked")
    public static List<StructureTemplate.JigsawBlockInfo> get(StructureTemplate template, BlockPos pos, Rotation rotation,
                                                              Operation<List<StructureTemplate.JigsawBlockInfo>> original) {
        if (!ENABLED || !BlockPos.ZERO.equals(pos) || !HOOK.allowed()) {
            return original.call(template, pos, rotation);
        }
        Template holder = (Template) template;
        List<?>[] lists = holder.ga2$jigsawLists();
        if (lists == null) {
            // two threads may both make an array. The one not kept loses only its lists
            lists = new List<?>[ROTATIONS];
            holder.ga2$jigsawLists(lists);
        }
        int slot = rotation.ordinal();
        List<StructureTemplate.JigsawBlockInfo> kept = (List<StructureTemplate.JigsawBlockInfo>) lists[slot];
        if (kept == null) {
            List<StructureTemplate.JigsawBlockInfo> made = original.call(template, pos, rotation);
            // an immutable copy has final fields, so another thread sees it whole
            lists[slot] = List.copyOf(made);
            MADE.increment();
            return made;
        }
        if (VERIFY_EVERY > 0 && VERIFY_COUNTER.incrementAndGet() % VERIFY_EVERY == 0) {
            List<StructureTemplate.JigsawBlockInfo> made = original.call(template, pos, rotation);
            VERIFIED.increment();
            if (!kept.equals(made)) {
                DIFFERING.increment();
                LOGGER.error("jigsaw list mismatch for rotation {}: expected {}, got {}", rotation, made, kept);
            }
            return made;
        }
        KEPT.increment();
        return new ArrayList<>(kept);
    }

    /** Drops a template's kept lists. Its palettes are being made again. */
    public static void forget(Template template) {
        template.ga2$jigsawLists(null);
    }
}
