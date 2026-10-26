package dev.denismasterherobrine.generatoraccelerator2.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.cache.CacheBuilder;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * {@link TemplateLoads} over vanilla's kind of map and ModernFix's (a Guava cache with soft values): every caller of an id gets the
 * one object in the map, each id loads once, and a load of one id does not hold up a lookup of another.
 */
public class TemplateLoadsTest {
    @SuppressWarnings("unchecked")
    private static final Operation<Object> COMPUTE = args -> ((Map<Object, Object>) args[0]).computeIfAbsent(args[1], (Function<Object, Object>) args[2]);

    private static List<Map<Object, Object>> maps() {
        return List.of(new ConcurrentHashMap<>(), CacheBuilder.newBuilder().softValues().build().asMap());
    }

    @Test
    void oneLoadPerIdAndOneObject() throws Exception {
        for (Map<Object, Object> map : maps()) {
            Map<Object, AtomicInteger> loads = new ConcurrentHashMap<>();
            Function<Object, Object> load = id -> {
                loads.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new Object[] { id };
            };
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                List<Future<Object[]>> asked = new ArrayList<>();
                for (int i = 0; i < 400; i++) {
                    String id = "template" + (i % 20);
                    asked.add(pool.submit(() -> new Object[] { id, TemplateLoads.get(map, id, load, COMPUTE) }));
                }
                for (Future<Object[]> f : asked) {
                    Object[] got = f.get(30, TimeUnit.SECONDS);
                    assertSame(map.get(got[0]), got[1], "a caller got another object than the map holds");
                }
            } finally {
                pool.shutdownNow();
            }
            assertEquals(20, loads.size());
            loads.forEach((id, n) -> assertEquals(1, n.get(), id + " loaded more than once"));
        }
    }

    @Test
    void aLoadHoldsUpOnlyItsOwnId() throws Exception {
        for (Map<Object, Object> map : maps()) {
            map.put("loaded", "kept");
            CountDownLatch loading = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Function<Object, Object> slow = id -> {
                loading.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "slow";
            };
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Object> first = pool.submit(() -> TemplateLoads.get(map, "big", slow, COMPUTE));
                assertTrue(loading.await(30, TimeUnit.SECONDS));
                // a loaded template and another template's load, while "big" loads
                Future<Object> other = pool.submit(() -> List.of(TemplateLoads.get(map, "loaded", id -> "again", COMPUTE),
                    TemplateLoads.get(map, "small", id -> "small", COMPUTE)));
                assertEquals(List.of("kept", "small"), other.get(10, TimeUnit.SECONDS));
                release.countDown();
                assertEquals("slow", first.get(30, TimeUnit.SECONDS));
                assertEquals("slow", TemplateLoads.get(map, "big", id -> "again", COMPUTE));
            } finally {
                release.countDown();
                pool.shutdownNow();
            }
        }
    }
}
