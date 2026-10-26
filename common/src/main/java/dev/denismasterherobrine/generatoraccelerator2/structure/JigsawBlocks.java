package dev.denismasterherobrine.generatoraccelerator2.structure;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * {@code StructureTemplate.filterBlocks} for jigsaw blocks. Jigsaw assembly calls it for every candidate piece and rotation,
 * and vanilla transforms each jigsaw block's position and rotates its state on every call. Consider a template with one
 * palette and settings that only rotate: no mirror, pivot at the origin, no bounding box, and no random of their own, so
 * picking the palette draws nothing. The transformed positions and rotated states then depend only on the palette and the
 * rotation. They are kept per palette and rotation, and each call only offsets the positions. The palette object is
 * replaced when a template is loaded again, which drops the cache along with it.
 */
public final class JigsawBlocks {
    /** {@code -Dgeneratoraccelerator2.jigsawCache=false} runs vanilla's {@code filterBlocks} on every call. */
    public static final boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.jigsawCache"));
    public static final LongAdder HITS = new LongAdder();

    private JigsawBlocks() {}

    /** Implemented by {@code StructureTemplate.Palette} (mixin). Holds one slot per rotation. */
    public interface PaletteCache {
        Object[] ga2$jigsaws();
    }

    private record Rotated(BlockPos[] pos, BlockState[] state, CompoundTag[] nbt) {}

    /** True when {@code filterBlocks(pos, settings, block, relative)} may be answered by {@link #filter}. */
    public static boolean applies(Object block, boolean relative, StructurePlaceSettings settings, Object random) {
        return ENABLED && relative && block == Blocks.JIGSAW && random == null && settings.getBoundingBox() == null
            && settings.getMirror() == Mirror.NONE && BlockPos.ZERO.equals(settings.getRotationPivot());
    }

    /** Vanilla's result for the jigsaw blocks of {@code palette} under {@code rotation}, offset by {@code pos}. */
    public static ObjectArrayList<StructureTemplate.StructureBlockInfo> filter(StructureTemplate.Palette palette, BlockPos pos, Rotation rotation) {
        Object[] slots = ((PaletteCache) (Object) palette).ga2$jigsaws();
        Rotated r = (Rotated) slots[rotation.ordinal()];
        if (r == null) {
            r = build(palette, rotation);
            slots[rotation.ordinal()] = r; // Immutable record. A racing thread builds an equal one.
        }
        HITS.increment();
        int n = r.pos.length;
        ObjectArrayList<StructureTemplate.StructureBlockInfo> list = new ObjectArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(new StructureTemplate.StructureBlockInfo(r.pos[i].offset(pos), r.state[i], r.nbt[i]));
        }
        return list;
    }

    private static Rotated build(StructureTemplate.Palette palette, Rotation rotation) {
        StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(rotation);
        List<StructureTemplate.StructureBlockInfo> blocks = palette.blocks(Blocks.JIGSAW);
        int n = blocks.size();
        BlockPos[] pos = new BlockPos[n];
        BlockState[] state = new BlockState[n];
        CompoundTag[] nbt = new CompoundTag[n];
        for (int i = 0; i < n; i++) {
            StructureTemplate.StructureBlockInfo info = blocks.get(i);
            pos[i] = StructureTemplate.calculateRelativePosition(settings, info.pos());
            state[i] = info.state().rotate(rotation);
            nbt[i] = info.nbt();
        }
        return new Rotated(pos, state, nbt);
    }
}
