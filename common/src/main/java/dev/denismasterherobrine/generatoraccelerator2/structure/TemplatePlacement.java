package dev.denismasterherobrine.generatoraccelerator2.structure;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.mixin.structure.StructurePlaceSettingsAccessor;
import dev.denismasterherobrine.generatoraccelerator2.platform.PlatformHooks;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlackstoneReplaceProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockAgeProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockRotProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.GravityProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.JigsawReplacementProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.LavaSubmergedBlockProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.NopProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.ProtectedBlockProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * {@code StructureTemplate.processBlockInfos} clipped to the piece's bounding box.
 *
 * <p>A structure piece is placed once per chunk it overlaps, with the chunk as its bounding box. Vanilla transforms every
 * block of the template first (two positions, a block info and a copy of its NBT per block), runs every processor on it,
 * and only then, in {@code placeInWorld}, drops the blocks outside the box. A village piece of a few thousand blocks does all
 * that for each of the four or more chunks it reaches, and a chunk does it for every piece reaching it.
 *
 * <p>Here the transformed position is computed first, as the ints vanilla's {@code transform} and {@code offset} compute, and
 * a block whose x or z lies outside the box is left out before anything is made or run for it. Its y is tested too unless a
 * {@code GravityProcessor} is among the processors, which moves blocks in y; the final position is still tested by
 * {@code placeInWorld} as before. The blocks kept get the same objects, in the same order, through the same processor calls
 * as vanilla's, so they come out the same.
 *
 * <p>Exact only when the blocks left out would not have changed anything else. Vanilla's processors decide each block from
 * that block alone (its state, the level at its position, a random seeded from its position), and their
 * {@code finalizeProcessing} returns the list it is given. So the list of blocks inside the box is the same with or without
 * the others. The exceptions stay on vanilla's path: {@code CappedProcessor}, whose finalize pass depends on the whole list;
 * any processor of another mod; a settings object with a random of its own, from which {@code BlockRotProcessor} and
 * {@code BlockAgeProcessor} would draw in list order; and settings without a bounding box. Another mod's change to
 * {@code placeInWorld}'s call, to {@code processBlockInfos} or to the position transform keeps vanilla's
 * ({@link HookTargets#STRUCTURE_TEMPLATE_CLIP}). {@code -Dgeneratoraccelerator2.templateClip=false} turns it off.
 */
public final class TemplatePlacement {
    private TemplatePlacement() {}

    /** Not final so the exactness test can compare both ways. */
    public static boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.templateClip"));
    private static final HookTargets.Hook HOOK = HookTargets.hook(HookTargets.STRUCTURE_TEMPLATE_CLIP);
    /** Placements clipped, placements left to vanilla, blocks left out before processing and blocks processed. */
    public static final LongAdder CLIPPED = new LongAdder();
    public static final LongAdder VANILLA = new LongAdder();
    public static final LongAdder SKIPPED = new LongAdder();
    public static final LongAdder PROCESSED = new LongAdder();

    /** Vanilla's processors that decide each block from that block alone and whose finalize pass is the identity. */
    private static final Set<Class<?>> PER_BLOCK = Set.of(BlockIgnoreProcessor.class, JigsawReplacementProcessor.class, RuleProcessor.class,
        BlockRotProcessor.class, GravityProcessor.class, ProtectedBlockProcessor.class, BlockAgeProcessor.class, LavaSubmergedBlockProcessor.class,
        BlackstoneReplaceProcessor.class, NopProcessor.class);

    /**
     * {@code processBlockInfos(level, offset, pivot, settings, blocks)} over the blocks that can land inside the settings' bounding
     * box, or null when the call has to run as vanilla's. {@code template} is Forge's extra argument (null on Fabric).
     */
    @Nullable
    public static List<StructureTemplate.StructureBlockInfo> clipped(ServerLevelAccessor level, BlockPos offset, BlockPos pivot, StructurePlaceSettings settings,
                                                                     List<StructureTemplate.StructureBlockInfo> blocks, @Nullable StructureTemplate template) {
        if (!ENABLED || !HOOK.allowed()) {
            return null;
        }
        BoundingBox box = settings.getBoundingBox();
        if (box == null) {
            VANILLA.increment();
            return null;
        }
        List<StructureProcessor> processors = settings.getProcessors();
        boolean gravity = false;
        boolean draws = false;
        for (StructureProcessor processor : processors) {
            Class<?> type = processor.getClass();
            if (!PER_BLOCK.contains(type)) {
                VANILLA.increment();
                return null;
            }
            gravity |= type == GravityProcessor.class;
            draws |= type == BlockRotProcessor.class || type == BlockAgeProcessor.class;
        }
        if (draws && ((StructurePlaceSettingsAccessor) settings).ga2$random() != null) {
            // a shared random draws once per block in list order
            VANILLA.increment();
            return null;
        }
        CLIPPED.increment();
        Mirror mirror = settings.getMirror();
        Rotation rotation = settings.getRotation();
        BlockPos rotationPivot = settings.getRotationPivot();
        int px = rotationPivot.getX();
        int pz = rotationPivot.getZ();
        int ox = offset.getX();
        int oy = offset.getY();
        int oz = offset.getZ();
        List<StructureTemplate.StructureBlockInfo> raw = new ArrayList<>();
        List<StructureTemplate.StructureBlockInfo> placed = new ArrayList<>();
        int skipped = 0;
        for (StructureTemplate.StructureBlockInfo info : blocks) {
            BlockPos p = info.pos();
            // StructureTemplate.transform(pos, mirror, rotation, pivot), then offset(offset)
            int i = p.getX();
            int j = p.getY();
            int k = p.getZ();
            switch (mirror) {
                case LEFT_RIGHT -> k = -k;
                case FRONT_BACK -> i = -i;
                default -> { }
            }
            int tx;
            int tz;
            switch (rotation) {
                case COUNTERCLOCKWISE_90 -> {
                    tx = px - pz + k;
                    tz = px + pz - i;
                }
                case CLOCKWISE_90 -> {
                    tx = px + pz - k;
                    tz = pz - px + i;
                }
                case CLOCKWISE_180 -> {
                    tx = px + px - i;
                    tz = pz + pz - k;
                }
                default -> {
                    tx = i;
                    tz = k;
                }
            }
            int x = tx + ox;
            int y = j + oy;
            int z = tz + oz;
            if (x < box.minX() || x > box.maxX() || z < box.minZ() || z > box.maxZ() || !gravity && (y < box.minY() || y > box.maxY())) {
                skipped++;
                continue;
            }
            StructureTemplate.StructureBlockInfo current = new StructureTemplate.StructureBlockInfo(new BlockPos(x, y, z), info.state(),
                info.nbt() != null ? info.nbt().copy() : null);
            for (int n = 0, count = processors.size(); current != null && n < count; n++) {
                current = PlatformHooks.processTemplateBlock(processors.get(n), level, offset, pivot, info, current, settings, template);
            }
            if (current != null) {
                placed.add(current);
                raw.add(info);
            }
        }
        SKIPPED.add(skipped);
        PROCESSED.add(blocks.size() - skipped);
        for (StructureProcessor processor : processors) {
            placed = processor.finalizeProcessing(level, offset, pivot, raw, placed, settings);
        }
        return placed;
    }

    public static String stats() {
        return "structure templates clipped to their box: " + CLIPPED.sum() + " placements (" + VANILLA.sum() + " left to vanilla), blocks left out "
            + SKIPPED.sum() + ", processed " + PROCESSED.sum() + "; " + TemplatePositions.counts() + "; templates loaded " + TemplateLoads.LOADED.sum()
            + ", lookups that waited for the same template " + TemplateLoads.WAITED.sum();
    }
}
