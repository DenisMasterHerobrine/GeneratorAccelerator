package dev.denismasterherobrine.generatoraccelerator2.dfc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.ExternSamplers;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Other mods' samplers are held by the compile that registered them, not by the static table. The table once held every
 * sampler and key strongly. A key is a world's density function or datapack config, so every world opened with Lithostitched
 * stayed in memory.
 */
public class ExternSamplersRetentionTest {
    @Test
    void samplersLiveWithTheirCompile() throws InterruptedException {
        List<ExternSamplers.Sampler> owned = new ArrayList<>();
        Object key = new Object();
        int index = ExternSamplers.own(() -> ExternSamplers.register(key, (x, y, z) -> x + y + z), owned);
        System.gc();
        assertEquals(6.0, ExternSamplers.sample(index, 1, 2, 3), "an owned sampler stays callable");
        assertEquals(index, ExternSamplers.own(() -> ExternSamplers.register(key, (x, y, z) -> -1), new ArrayList<>()),
            "the same key keeps its slot");
        assertEquals(6.0, ExternSamplers.sample(index, 1, 2, 3), "and its sampler");

        WeakReference<Object> gone = dropped();
        for (int i = 0; i < 40 && gone.get() != null; i++) {
            System.gc();
            Thread.sleep(25);
        }
        assertNull(gone.get(), "a key whose compile is gone is not kept");
        List<ExternSamplers.Sampler> next = new ArrayList<>();
        int reused = ExternSamplers.own(() -> ExternSamplers.register(new Object(), (x, y, z) -> 42), next);
        assertEquals(42.0, ExternSamplers.sample(reused, 0, 0, 0), "a new sampler is callable");
        assertEquals(1, next.size(), "the compile owns what it registered");
    }

    /** Registers a sampler under a fresh key in a compile that is then dropped. Returns the key, weakly. */
    private static WeakReference<Object> dropped() {
        Object key = new Object();
        List<ExternSamplers.Sampler> owned = new ArrayList<>();
        ExternSamplers.own(() -> ExternSamplers.register(key, (x, y, z) -> 0), owned);
        return new WeakReference<>(key);
    }

    @Test
    void samplersOutsideACompileAreKept() throws InterruptedException {
        int index = ExternSamplers.register(new Object(), (x, y, z) -> 7);
        for (int i = 0; i < 5; i++) {
            System.gc();
            Thread.sleep(10);
        }
        assertEquals(7.0, ExternSamplers.sample(index, 0, 0, 0), "IR built outside a compile (tests) still calls its sampler");
    }
}
