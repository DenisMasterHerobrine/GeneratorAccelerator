package dev.denismasterherobrine.generatoraccelerator2.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.junit.jupiter.api.Test;

/**
 * A template palette's blocks by block ({@code StructureTemplatePaletteMixin}): decorations on several threads may ask one palette
 * at once (Oh The Trees You'll Grow's trees from structure files), so its memo is a concurrent map, and every answer holds all the
 * palette's blocks of that block.
 */
class TemplatePaletteThreadsTest {
    @Test
    void manyThreadsGetEachBlocksList() throws Exception {
        List<Block> kinds = BuiltInRegistries.BLOCK.stream().limit(200).toList();
        List<StructureTemplate.StructureBlockInfo> infos = new ArrayList<>();
        for (int i = 0; i < 4000; i++) {
            infos.add(new StructureTemplate.StructureBlockInfo(new BlockPos(i & 15, i >> 8, (i >> 4) & 15), kinds.get(i % kinds.size()).defaultBlockState(), null));
        }
        Constructor<StructureTemplate.Palette> made = StructureTemplate.Palette.class.getDeclaredConstructor(List.class);
        made.setAccessible(true);
        StructureTemplate.Palette palette = made.newInstance(infos);
        Field cache = StructureTemplate.Palette.class.getDeclaredField("cache");
        cache.setAccessible(true);
        assertInstanceOf(ConcurrentHashMap.class, (Map<?, ?>) cache.get(palette), "the palette's memo is a plain map");

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> done = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            int offset = t * 25;
            done.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < kinds.size(); i++) {
                    Block b = kinds.get((i + offset) % kinds.size());
                    List<StructureTemplate.StructureBlockInfo> got = palette.blocks(b);
                    long expected = infos.stream().filter(x -> x.state().is(b)).count();
                    assertEquals(expected, got.size(), b.toString());
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : done) {
            f.get();
        }
        pool.shutdown();
    }
}
