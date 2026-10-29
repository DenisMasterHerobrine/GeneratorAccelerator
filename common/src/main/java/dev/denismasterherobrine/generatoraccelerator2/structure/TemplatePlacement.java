package dev.denismasterherobrine.generatoraccelerator2.structure;

import dev.denismasterherobrine.generatoraccelerator2.chunkio.VerifyCounter;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.mixin.structure.CappedProcessorAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.structure.StructurePlaceSettingsAccessor;
import dev.denismasterherobrine.generatoraccelerator2.platform.PlatformHooks;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlackstoneReplaceProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockAgeProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockRotProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.CappedProcessor;
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
 * the others. {@code CappedProcessor}'s finalize pass depends on the whole list. Lists with it go through
 * {@link CappedPlacement}. Processors of other mods are taken when compat code knows them ({@link #addClassifier}). The rest
 * stays on vanilla's path: any other processor, a settings object with a random of its own, from which
 * {@code BlockRotProcessor} and {@code BlockAgeProcessor} would draw in list order, and settings without a bounding box.
 * Another mod's change to {@code placeInWorld}'s call, to {@code processBlockInfos} or to the position transform keeps
 * vanilla's ({@link HookTargets#STRUCTURE_TEMPLATE_CLIP}). {@code -Dgeneratoraccelerator2.templateClip=false} turns it off.
 */
public final class TemplatePlacement {
    private TemplatePlacement() {}

    /** Not final so the exactness test can compare both ways. */
    public static boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.templateClip"));
    private static final HookTargets.Hook HOOK = HookTargets.hook(HookTargets.STRUCTURE_TEMPLATE_CLIP);
    /** Placements clipped, placements left to vanilla, blocks left out before processing and blocks processed. */
    public static final LongAdder CLIPPED = new LongAdder();
    public static final LongAdder VANILLA = new LongAdder();
    /** Clipped placements with a capped processor ({@link CappedPlacement}). */
    public static final LongAdder CAPPED = new LongAdder();
    /** Why placements were left to vanilla: the first processor not known to work block by block, or the reason. */
    private static final ConcurrentHashMap<String, LongAdder> STOOD_DOWN = new ConcurrentHashMap<>();

    /** Counts a placement left to vanilla and why. Returns null, the answer for vanilla's path. */
    @Nullable
    static List<StructureTemplate.StructureBlockInfo> standDown(String why) {
        VANILLA.increment();
        STOOD_DOWN.computeIfAbsent(why, k -> new LongAdder()).increment();
        return null;
    }
    public static final LongAdder SKIPPED = new LongAdder();
    public static final LongAdder PROCESSED = new LongAdder();

    /** Vanilla's processors that decide each block from that block alone and whose finalize pass is the identity. */
    private static final Set<Class<?>> PER_BLOCK = Set.of(BlockIgnoreProcessor.class, JigsawReplacementProcessor.class, RuleProcessor.class,
        BlockRotProcessor.class, GravityProcessor.class, ProtectedBlockProcessor.class, BlockAgeProcessor.class, LavaSubmergedBlockProcessor.class,
        BlackstoneReplaceProcessor.class, NopProcessor.class);

    /**
     * How a processor works, for the clip: block by block (else it is unknown and the placement runs as vanilla's), whether it
     * draws from the settings' random (shared by every block when the settings have one), and whether it moves blocks in y.
     * {@code center}: it writes the level for blocks inside the chunk being generated (a region's center) and leaves other
     * blocks pure. The clip then leaves out only blocks outside that chunk. {@code unchanged}: it hands every block back as it
     * gets it, so lists with a capped processor take it like vanilla's no-op ({@link CappedPlacement}).
     */
    public record Kind(boolean draws, boolean gravity, boolean center, boolean unchanged) {
        public Kind and(Kind other) {
            return new Kind(draws || other.draws, gravity || other.gravity, center || other.center, unchanged && other.unchanged);
        }
    }

    public static final Kind PLAIN = new Kind(false, false, false, false);
    public static final Kind DRAWS = new Kind(true, false, false, false);
    public static final Kind NO_OP = new Kind(false, false, false, true);
    /** Writes the level inside the generating chunk only, and draws from the settings' random. */
    public static final Kind CENTER_DRAWS = new Kind(true, false, true, false);

    /**
     * Compat code's verdict on a processor of its mod: its kind, or null when that processor cannot be clipped. {@code registries}
     * are the level's, for a processor that refers to registry entries.
     */
    public interface Classifier {
        @Nullable
        Kind classify(StructureProcessor processor, RegistryAccess registries);
    }

    private static final List<Classifier> CLASSIFIERS = new CopyOnWriteArrayList<>();

    /** Registered while mods load ({@code compat}). A classifier answers null for the processors it does not know. */
    public static void addClassifier(Classifier classifier) {
        CLASSIFIERS.add(classifier);
    }

    /** The kind of {@code processor}: vanilla's per-block processors and those compat code knows. Null when it is unknown. */
    @Nullable
    public static Kind kind(StructureProcessor processor, RegistryAccess registries) {
        Class<?> type = processor.getClass();
        if (PER_BLOCK.contains(type)) {
            return new Kind(type == BlockRotProcessor.class || type == BlockAgeProcessor.class, type == GravityProcessor.class, false, type == NopProcessor.class);
        }
        for (Classifier classifier : CLASSIFIERS) {
            Kind kind = classifier.classify(processor, registries);
            if (kind != null) {
                return kind;
            }
        }
        return null;
    }

    /**
     * {@code processBlockInfos(level, offset, pivot, settings, blocks)} over the blocks that can land inside the settings' bounding
     * box, or null when the call has to run as vanilla's. {@code template} is NeoForge's extra argument (null on Fabric).
     */
    @Nullable
    public static List<StructureTemplate.StructureBlockInfo> clipped(ServerLevelAccessor level, BlockPos offset, BlockPos pivot, StructurePlaceSettings settings,
                                                                     List<StructureTemplate.StructureBlockInfo> blocks, @Nullable StructureTemplate template) {
        if (!ENABLED || !HOOK.allowed()) {
            return null;
        }
        BoundingBox box = settings.getBoundingBox();
        if (box == null) {
            return standDown("no box");
        }
        List<StructureProcessor> processors = settings.getProcessors();
        boolean gravity = false;
        boolean draws = false;
        boolean center = false;
        boolean capped = false;
        RegistryAccess registries = level.registryAccess();
        for (StructureProcessor processor : processors) {
            StructureProcessor judged = processor;
            if (processor.getClass() == CappedProcessor.class) {
                // its own block call returns the block. Its finalize pass runs the delegate.
                capped = true;
                judged = ((CappedProcessorAccessor) processor).ga2$delegate();
            }
            Kind kind = kind(judged, registries);
            if (kind == null) {
                return standDown(judged == processor ? processor.getClass().getName() : "CappedProcessor of " + judged.getClass().getName());
            }
            gravity |= kind.gravity();
            draws |= kind.draws();
            center |= kind.center();
        }
        if (draws && ((StructurePlaceSettingsAccessor) settings).ga2$random() != null) {
            // a shared random draws once per block in list order
            return standDown("a shared random");
        }
        if (center && !(level instanceof WorldGenRegion region && covers(box, region.getCenter()))) {
            return standDown("a box short of the generating chunk");
        }
        List<StructureTemplate.StructureBlockInfo> placed;
        if (capped) {
            placed = CappedPlacement.clipped(level, offset, pivot, settings, blocks, template, box);
            if (placed == null) {
                return null;
            }
        } else {
            CLIPPED.increment();
            placed = perBlock(level, offset, pivot, settings, blocks, template, box, gravity || center);
        }
        // other mods' handlers at the method's return, which GA2 runs here because it answers the call itself
        for (ReturnStep step : RETURN_STEPS) {
            placed = step.apply(level, offset, pivot, settings, blocks, template, placed);
        }
        return placed;
    }

    /** True when the box holds the whole chunk in x and z. */
    private static boolean covers(BoundingBox box, ChunkPos chunk) {
        return box.minX() <= chunk.getMinBlockX() && box.maxX() >= chunk.getMaxBlockX() && box.minZ() <= chunk.getMinBlockZ() && box.maxZ() >= chunk.getMaxBlockZ();
    }

    /** The clip for processors that work block by block. {@code anyY}: blocks are not left out by their y. */
    private static List<StructureTemplate.StructureBlockInfo> perBlock(ServerLevelAccessor level, BlockPos offset, BlockPos pivot, StructurePlaceSettings settings,
                                                                       List<StructureTemplate.StructureBlockInfo> blocks, @Nullable StructureTemplate template,
                                                                       BoundingBox box, boolean anyY) {
        List<StructureProcessor> processors = settings.getProcessors();
        Mirror mirror = settings.getMirror();
        Rotation rotation = settings.getRotation();
        BlockPos rotationPivot = settings.getRotationPivot();
        List<StructureTemplate.StructureBlockInfo> raw = new ArrayList<>();
        List<StructureTemplate.StructureBlockInfo> placed = new ArrayList<>();
        int skipped = 0;
        for (StructureTemplate.StructureBlockInfo info : blocks) {
            long at = place(info.pos(), mirror, rotation, rotationPivot, offset);
            int x = BlockPos.getX(at);
            int y = BlockPos.getY(at);
            int z = BlockPos.getZ(at);
            if (x < box.minX() || x > box.maxX() || z < box.minZ() || z > box.maxZ() || !anyY && (y < box.minY() || y > box.maxY())) {
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

    /**
     * Where a template position lands, packed ({@link BlockPos#asLong}): {@code StructureTemplate.transform(pos, mirror, rotation,
     * pivot)}, then {@code offset(offset)}, in ints.
     */
    static long place(BlockPos p, Mirror mirror, Rotation rotation, BlockPos pivot, BlockPos offset) {
        int i = p.getX();
        int k = p.getZ();
        switch (mirror) {
            case LEFT_RIGHT -> k = -k;
            case FRONT_BACK -> i = -i;
            default -> { }
        }
        int px = pivot.getX();
        int pz = pivot.getZ();
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
        return BlockPos.asLong(tx + offset.getX(), p.getY() + offset.getY(), tz + offset.getZ());
    }

    /**
     * {@code -Dgeneratoraccelerator2.verify.templateClip=N}: every N-th clipped placement is also processed whole, as vanilla processes
     * it, and the blocks inside the box that the placement writes are compared.
     */
    public static final VerifyCounter VERIFY = new VerifyCounter("templateClip");

    /** The clipped result against vanilla's whole one, inside the settings' box: the same blocks in the same order. */
    public static void verify(StructurePlaceSettings settings, List<StructureTemplate.StructureBlockInfo> clipped, List<StructureTemplate.StructureBlockInfo> vanilla) {
        BoundingBox box = settings.getBoundingBox();
        List<StructureTemplate.StructureBlockInfo> expected = new ArrayList<>();
        for (StructureTemplate.StructureBlockInfo info : vanilla) {
            if (box == null || box.isInside(info.pos())) {
                expected.add(info);
            }
        }
        List<StructureTemplate.StructureBlockInfo> got = new ArrayList<>();
        for (StructureTemplate.StructureBlockInfo info : clipped) {
            if (box == null || box.isInside(info.pos())) {
                got.add(info);
            }
        }
        int differ = -1;
        for (int i = 0; i < Math.min(expected.size(), got.size()); i++) {
            if (!expected.get(i).equals(got.get(i))) {
                differ = i;
                break;
            }
        }
        boolean same = differ < 0 && expected.size() == got.size();
        VERIFY.result(same, box + " with " + settings.getProcessors().stream().map(p -> p.getClass().getSimpleName()).toList(),
            expected.size() + " blocks" + (differ >= 0 ? ", block " + differ + " " + expected.get(differ) : ""),
            got.size() + " blocks" + (differ >= 0 ? ", block " + differ + " " + got.get(differ) : ""));
    }

    /** A step another mod's handler adds at the return of {@code processBlockInfos}, run by compat code on the clipped result. */
    public interface ReturnStep {
        List<StructureTemplate.StructureBlockInfo> apply(ServerLevelAccessor level, BlockPos offset, BlockPos pivot, StructurePlaceSettings settings,
                                                         List<StructureTemplate.StructureBlockInfo> blocks, @Nullable StructureTemplate template,
                                                         List<StructureTemplate.StructureBlockInfo> result);
    }

    private static final List<ReturnStep> RETURN_STEPS = new CopyOnWriteArrayList<>();

    /** Registered while mods load ({@code compat}), for a handler the clip hook knows. */
    public static void addReturnStep(ReturnStep step) {
        RETURN_STEPS.add(step);
    }

    public static String stats() {
        return "structure templates clipped to their box: " + CLIPPED.sum() + " placements (" + CAPPED.sum() + " capped, " + VANILLA.sum()
            + " left to vanilla), blocks left out "
            + SKIPPED.sum() + ", processed " + PROCESSED.sum() + "; " + TemplatePositions.counts() + "; templates loaded " + TemplateLoads.LOADED.sum()
            + ", lookups that waited for the same template " + TemplateLoads.WAITED.sum()
            + "; jigsaw lists kept " + JigsawLists.MADE.sum() + ", handed out again " + JigsawLists.KEPT.sum()
            + (JigsawLists.VERIFIED.sum() > 0 ? " (verified " + JigsawLists.VERIFIED.sum() + ", differing " + JigsawLists.DIFFERING.sum() + ")" : "")
            + (STOOD_DOWN.isEmpty() ? "" : "; left to vanilla for " + STOOD_DOWN.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum())).limit(6)
                .map(e -> e.getKey() + " " + e.getValue().sum()).collect(Collectors.joining(", ")));
    }
}
