package dev.denismasterherobrine.generatoraccelerator2.structure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.features.FeatureRun;
import dev.denismasterherobrine.generatoraccelerator2.features.HarnessLevel;
import dev.denismasterherobrine.generatoraccelerator2.features.HarnessWorld;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.AlwaysTrueTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlackstoneReplaceProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockAgeProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockRotProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.GravityProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.JigsawReplacementProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.LavaSubmergedBlockProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.ProcessorRule;
import net.minecraft.world.level.levelgen.structure.templatesystem.ProtectedBlockProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.RandomBlockMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.junit.jupiter.api.Test;

/**
 * A template placed with its blocks clipped to the bounding box before they are processed ({@link TemplatePlacement}) leaves
 * the chunks as vanilla's placement leaves them: a large random template straddling the chunk, every rotation and mirror, a
 * pivot, and each of vanilla's per-block processors, against vanilla's own {@code processBlockInfos} on identical chunks.
 */
public class TemplateClipTest {
    @Test
    void clippedPlacementMatchesVanilla() {
        HarnessWorld.Registries2 r = HarnessWorld.registries();
        Random rng = new Random(20261023L);
        StructureTemplate template = template(rng);
        DimensionType dim = HarnessWorld.dimension(r, HarnessWorld.Kind.OVERWORLD);
        LevelHeightAccessor heights = LevelHeightAccessor.create(dim.minY(), dim.height());
        int cx = FeatureRun.CENTER_X;
        int cz = FeatureRun.CENTER_Z;
        BoundingBox chunkBox = new BoundingBox(cx * 16, dim.minY(), cz * 16, cx * 16 + 15, dim.minY() + dim.height() - 1, cz * 16 + 15);
        List<List<StructureProcessor>> processorSets = List.of(
            List.of(),
            List.of(BlockIgnoreProcessor.STRUCTURE_BLOCK, JigsawReplacementProcessor.INSTANCE),
            List.of(new BlockRotProcessor(0.6F)),
            List.of(new GravityProcessor(Heightmap.Types.WORLD_SURFACE_WG, -1), JigsawReplacementProcessor.INSTANCE),
            List.of(new RuleProcessor(List.of(new ProcessorRule(new RandomBlockMatchTest(Blocks.STONE, 0.3F), AlwaysTrueTest.INSTANCE,
                Blocks.MOSSY_COBBLESTONE.defaultBlockState())))),
            List.of(new ProtectedBlockProcessor(BlockTags.FEATURES_CANNOT_REPLACE), new BlockAgeProcessor(0.5F)),
            List.of(LavaSubmergedBlockProcessor.INSTANCE, BlackstoneReplaceProcessor.INSTANCE));
        // the jigsaw replacement parses its final state through the level's block registry
        RegistryAccess access = new RegistryAccess.ImmutableRegistryAccess(List.of(r.biomes, BuiltInRegistries.BLOCK));
        long clippedBefore = TemplatePlacement.CLIPPED.sum();
        long skippedBefore = TemplatePlacement.SKIPPED.sum();
        int compared = 0;
        int placedCount = 0;
        for (int variant = 0; variant < 40; variant++) {
            long seed = rng.nextLong();
            Rotation rotation = Rotation.values()[variant % 4];
            Mirror mirror = Mirror.values()[(variant / 4) % 3];
            BlockPos pivot = variant % 5 == 0 ? new BlockPos(3, 0, 5) : BlockPos.ZERO;
            BlockPos offset = new BlockPos(cx * 16 - 9 + rng.nextInt(19), 55 + rng.nextInt(20), cz * 16 - 9 + rng.nextInt(19));
            List<StructureProcessor> processors = processorSets.get(variant % processorSets.size());
            ProtoChunk[] terrain = HarnessWorld.terrain(r, HarnessWorld.Kind.OVERWORLD, seed, cx, cz, i -> r.biomes.getHolderOrThrow(Biomes.PLAINS));
            boolean[] placed = new boolean[2];
            ProtoChunk[][] after = new ProtoChunk[2][];
            for (int run = 0; run < 2; run++) {
                ProtoChunk[] chunks = HarnessWorld.clone(r, terrain, heights);
                HarnessLevel level = new HarnessLevel(chunks, HarnessWorld.RADIUS, cx, cz, heights, dim, seed, HarnessWorld.randomState(r, HarnessWorld.Kind.OVERWORLD, seed),
                    access, false);
                StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(rotation).setMirror(mirror).setRotationPivot(pivot)
                    .setBoundingBox(chunkBox).setKnownShape(true).setIgnoreEntities(true);
                processors.forEach(settings::addProcessor);
                TemplatePlacement.ENABLED = run == 1;
                try {
                    placed[run] = template.placeInWorld(level, offset, offset.offset(pivot), settings, new XoroshiroRandomSource(seed), 2);
                } finally {
                    TemplatePlacement.ENABLED = true;
                }
                after[run] = chunks;
            }
            String where = "variant " + variant + " " + rotation + " " + mirror + " pivot " + pivot + " at " + offset + " processors " + processors;
            assertEquals(placed[0], placed[1], "placed, " + where);
            for (int i = 0; i < after[0].length; i++) {
                sameChunk(after[0][i], after[1][i], "chunk " + i + ", " + where);
            }
            compared++;
            if (placed[0]) {
                placedCount++;
            }
        }
        long clipped = TemplatePlacement.CLIPPED.sum() - clippedBefore;
        long skipped = TemplatePlacement.SKIPPED.sum() - skippedBefore;
        assertEquals(compared, clipped, "every placement with the clip on was clipped");
        assertTrue(skipped > 0, "blocks were left out before processing");
        assertTrue(placedCount > compared / 2, "placements that placed blocks: " + placedCount);
        System.out.println("TemplateClipTest: " + compared + " placements identical to vanilla's; " + skipped + " template blocks left out before processing");
    }

    /** A 24x14x24 template of about 3000 random blocks: stone, planks, stairs, slabs, water, lava, air gaps and jigsaw blocks with a final state. */
    private static StructureTemplate template(Random rng) {
        BlockState[] palette = {
            Blocks.STONE.defaultBlockState(), Blocks.OAK_PLANKS.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(),
            Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST),
            Blocks.STONE_BRICK_SLAB.defaultBlockState(), Blocks.WATER.defaultBlockState(), Blocks.LAVA.defaultBlockState(), Blocks.AIR.defaultBlockState(),
            Blocks.JIGSAW.defaultBlockState(), Blocks.STONE_BRICKS.defaultBlockState(), Blocks.MOSSY_COBBLESTONE_WALL.defaultBlockState(),
            Blocks.OBSIDIAN.defaultBlockState(), Blocks.STRUCTURE_VOID.defaultBlockState()
        };
        CompoundTag tag = new CompoundTag();
        ListTag size = new ListTag();
        size.add(IntTag.valueOf(24));
        size.add(IntTag.valueOf(14));
        size.add(IntTag.valueOf(24));
        tag.put("size", size);
        ListTag paletteTag = new ListTag();
        for (BlockState state : palette) {
            paletteTag.add(NbtUtils.writeBlockState(state));
        }
        tag.put("palette", paletteTag);
        ListTag blocks = new ListTag();
        for (int i = 0; i < 3000; i++) {
            CompoundTag block = new CompoundTag();
            ListTag pos = new ListTag();
            pos.add(IntTag.valueOf(rng.nextInt(24)));
            pos.add(IntTag.valueOf(rng.nextInt(14)));
            pos.add(IntTag.valueOf(rng.nextInt(24)));
            block.put("pos", pos);
            int state = rng.nextInt(palette.length);
            block.putInt("state", state);
            if (palette[state].is(Blocks.JIGSAW)) {
                CompoundTag nbt = new CompoundTag();
                nbt.putString("name", "minecraft:empty");
                nbt.putString("target", "minecraft:empty");
                nbt.putString("pool", "minecraft:empty");
                nbt.putString("final_state", rng.nextBoolean() ? "minecraft:stone_bricks" : "minecraft:air");
                nbt.putString("joint", "rollable");
                block.put("nbt", nbt);
            }
            blocks.add(block);
        }
        tag.put("blocks", blocks);
        tag.put("entities", new ListTag());
        StructureTemplate template = new StructureTemplate();
        template.load(BuiltInRegistries.BLOCK.asLookup(), tag);
        return template;
    }

    static void sameChunk(ProtoChunk a, ProtoChunk b, String where) {
        LevelChunkSection[] sa = a.getSections();
        LevelChunkSection[] sb = b.getSections();
        for (int s = 0; s < sa.length; s++) {
            assertEquals(sa[s].nonEmptyBlockCount, sb[s].nonEmptyBlockCount, "non-empty count, section " + s + ", " + where);
            assertEquals(sa[s].tickingFluidCount, sb[s].tickingFluidCount, "fluid count, section " + s + ", " + where);
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        assertSame(sa[s].getBlockState(x, y, z), sb[s].getBlockState(x, y, z), "block " + x + "," + (s * 16 + y) + "," + z + ", " + where);
                    }
                }
            }
        }
        for (Heightmap.Types type : Heightmap.Types.values()) {
            assertEquals(a.hasPrimedHeightmap(type), b.hasPrimedHeightmap(type), "heightmap presence " + type + ", " + where);
            if (a.hasPrimedHeightmap(type)) {
                assertArrayEquals(a.getOrCreateHeightmapUnprimed(type).getRawData(), b.getOrCreateHeightmapUnprimed(type).getRawData(), "heightmap " + type + ", " + where);
            }
        }
        assertEquals(Arrays.deepToString(a.getPostProcessing()), Arrays.deepToString(b.getPostProcessing()), "post-processing, " + where);
    }
}
