package dev.denismasterherobrine.generatoraccelerator2.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.minecraft.core.BlockPos;
import net.minecraft.core.FrontAndTop;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.junit.jupiter.api.Test;

/**
 * {@link JigsawLists} against vanilla's {@code getJigsaws}: the same list for every rotation at position zero, a new mutable list
 * per call, other positions left to vanilla, the lists dropped when the template loads again, and the same lists from parallel
 * callers. The template has three palettes, so the palette pick at position zero matters.
 */
public class JigsawListsTest {
    private static final Operation<List<StructureTemplate.JigsawBlockInfo>> VANILLA =
        args -> ((StructureTemplate) args[0]).getJigsaws((BlockPos) args[1], (Rotation) args[2]);

    private static StructureTemplate template() throws ReflectiveOperationException {
        // the hook is inspected when the class with the wrapped call is transformed
        Class.forName(SinglePoolElement.class.getName());
        StructureTemplate template = new StructureTemplate();
        Constructor<StructureTemplate.Palette> make = StructureTemplate.Palette.class.getDeclaredConstructor(List.class);
        make.setAccessible(true);
        Field palettes = StructureTemplate.class.getDeclaredField("palettes");
        palettes.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<StructureTemplate.Palette> list = (List<StructureTemplate.Palette>) palettes.get(template);
        FrontAndTop[] orientations = FrontAndTop.values();
        Random random = new Random(7);
        for (int p = 0; p < 3; p++) {
            List<StructureTemplate.StructureBlockInfo> blocks = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                BlockPos pos = new BlockPos(random.nextInt(9), random.nextInt(9), random.nextInt(9));
                if (i % 3 == 0) {
                    blocks.add(new StructureTemplate.StructureBlockInfo(pos, Blocks.STONE.defaultBlockState(), null));
                    continue;
                }
                CompoundTag nbt = new CompoundTag();
                nbt.putString("name", "ga2:palette" + p + "_jigsaw" + i);
                nbt.putString("target", "ga2:target" + (i % 4));
                nbt.putInt("selection_priority", i % 2);
                blocks.add(new StructureTemplate.StructureBlockInfo(pos,
                    Blocks.JIGSAW.defaultBlockState().setValue(JigsawBlock.ORIENTATION, orientations[random.nextInt(orientations.length)]), nbt));
            }
            list.add(make.newInstance(blocks));
        }
        return template;
    }

    @Test
    void sameListsAsVanilla() throws Exception {
        StructureTemplate template = template();
        long made = JigsawLists.MADE.sum();
        long kept = JigsawLists.KEPT.sum();
        for (int round = 0; round < 3; round++) {
            for (Rotation rotation : Rotation.values()) {
                List<StructureTemplate.JigsawBlockInfo> expected = template.getJigsaws(BlockPos.ZERO, rotation);
                List<StructureTemplate.JigsawBlockInfo> got = JigsawLists.get(template, BlockPos.ZERO, rotation, VANILLA);
                assertEquals(expected, got, "rotation " + rotation + ", round " + round);
                assertTrue(got instanceof ArrayList<?>, "callers shuffle the list in place");
                // the caller's shuffle must not reach the kept list
                Collections.shuffle(got, new Random(round));
                assertEquals(expected, JigsawLists.get(template, BlockPos.ZERO, rotation, VANILLA), "after a caller shuffled its copy");
                assertNotSame(got, JigsawLists.get(template, BlockPos.ZERO, rotation, VANILLA), "a new list per call");
            }
        }
        assertEquals(made + Rotation.values().length, JigsawLists.MADE.sum(), "one list made per rotation");
        assertTrue(JigsawLists.KEPT.sum() > kept, "no list handed out from the kept ones (hook not allowed?)");
    }

    @Test
    void otherPositionsGoToVanilla() throws Exception {
        StructureTemplate template = template();
        long made = JigsawLists.MADE.sum();
        BlockPos pos = new BlockPos(5, 64, -3);
        for (Rotation rotation : Rotation.values()) {
            assertEquals(template.getJigsaws(pos, rotation), JigsawLists.get(template, pos, rotation, VANILLA));
        }
        assertEquals(made, JigsawLists.MADE.sum(), "a list kept for a position other than zero");
    }

    @Test
    void loadingAgainDropsTheLists() throws Exception {
        StructureTemplate template = template();
        JigsawLists.get(template, BlockPos.ZERO, Rotation.CLOCKWISE_90, VANILLA);
        assertTrue(((JigsawLists.Template) template).ga2$jigsawLists() != null);
        template.load(BuiltInRegistries.BLOCK, new CompoundTag());
        assertNull(((JigsawLists.Template) template).ga2$jigsawLists(), "lists of the old palettes kept");
        assertEquals(template.getJigsaws(BlockPos.ZERO, Rotation.CLOCKWISE_90), JigsawLists.get(template, BlockPos.ZERO, Rotation.CLOCKWISE_90, VANILLA));
    }

    @Test
    void parallelCallersGetVanillasLists() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < 20; round++) {
                StructureTemplate template = template();
                CountDownLatch start = new CountDownLatch(1);
                List<Future<List<StructureTemplate.JigsawBlockInfo>>> asked = new ArrayList<>();
                for (int t = 0; t < 64; t++) {
                    Rotation rotation = Rotation.values()[t & 3];
                    asked.add(pool.submit(() -> {
                        start.await();
                        return JigsawLists.get(template, BlockPos.ZERO, rotation, VANILLA);
                    }));
                }
                start.countDown();
                for (int t = 0; t < asked.size(); t++) {
                    assertEquals(template.getJigsaws(BlockPos.ZERO, Rotation.values()[t & 3]), asked.get(t).get());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
