package dev.denismasterherobrine.generatoraccelerator2.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.junit.jupiter.api.Test;

/** A template palette's per-block lists are filled by parallel structure starts ({@code StructureTemplatePaletteMixin}). */
public class TemplatePaletteCacheTest {
    @Test
    void parallelReadsOfOnePaletteGiveVanillasLists() throws Exception {
        List<StructureTemplate.StructureBlockInfo> blocks = new ArrayList<>();
        Block[] kinds = { Blocks.JIGSAW, Blocks.STONE, Blocks.JIGSAW, Blocks.OAK_PLANKS, Blocks.JIGSAW };
        for (int i = 0; i < 2000; i++) {
            blocks.add(new StructureTemplate.StructureBlockInfo(new BlockPos(i & 15, i >> 8, (i >> 4) & 15), kinds[i % kinds.length].defaultBlockState(), null));
        }
        Constructor<StructureTemplate.Palette> make = StructureTemplate.Palette.class.getDeclaredConstructor(List.class);
        make.setAccessible(true);
        Field cache = StructureTemplate.Palette.class.getDeclaredField("cache");
        cache.setAccessible(true);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < 50; round++) {
                StructureTemplate.Palette palette = make.newInstance(blocks);
                assertTrue(cache.get(palette) instanceof ConcurrentHashMap<?, ?>, "the cache is concurrent");
                CountDownLatch start = new CountDownLatch(1);
                List<Future<List<StructureTemplate.StructureBlockInfo>>> reads = new ArrayList<>();
                for (int t = 0; t < 8; t++) {
                    Block kind = kinds[t % 3 + (t % 2)];
                    reads.add(pool.submit(() -> {
                        start.await();
                        return palette.blocks(kind);
                    }));
                }
                start.countDown();
                for (int t = 0; t < reads.size(); t++) {
                    Block kind = kinds[t % 3 + (t % 2)];
                    List<StructureTemplate.StructureBlockInfo> got = reads.get(t).get();
                    assertEquals(blocks.stream().filter(b -> b.state().is(kind)).toList(), got, "vanilla's filter, in list order");
                }
                assertTrue(((Map<?, ?>) cache.get(palette)).size() <= 3);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
