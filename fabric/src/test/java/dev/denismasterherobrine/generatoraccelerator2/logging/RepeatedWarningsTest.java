package dev.denismasterherobrine.generatoraccelerator2.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.features.HarnessWorld;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.function.Supplier;
import net.minecraft.SystemReport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * Vanilla's world generation warnings are kept out of the log by default and counted per source. A pack with one broken
 * feature logged the same lines thousands of times from the generation threads.
 */
public class RepeatedWarningsTest {
    @BeforeEach
    void forget() {
        RepeatedWarnings.serverStopped();
    }

    @Test
    void poiMismatchesCountedPerTypePair() {
        Holder<PoiType> armorer = BuiltInRegistries.POINT_OF_INTEREST_TYPE.getHolderOrThrow(PoiTypes.ARMORER);
        Holder<PoiType> butcher = BuiltInRegistries.POINT_OF_INTEREST_TYPE.getHolderOrThrow(PoiTypes.BUTCHER);
        PoiSection section = new PoiSection(() -> {});
        String pair = HolderNames.name(armorer) + " -> " + HolderNames.name(butcher);
        for (int i = 0; i < 3; i++) {
            BlockPos pos = new BlockPos(i, 0, 0);
            section.add(pos, armorer);
            section.add(pos, butcher);
            assertTrue(section.exists(pos, h -> h.equals(butcher)), "the type added still replaces the one registered");
        }
        assertEquals(2, RepeatedWarnings.repeats(RepeatedWarnings.Kind.POI_MISMATCH, pair), "the first mismatch is logged, the two after it counted");
        section.add(new BlockPos(5, 0, 0), butcher);
        section.add(new BlockPos(5, 0, 0), armorer);
        assertEquals(0, RepeatedWarnings.repeats(RepeatedWarnings.Kind.POI_MISMATCH, HolderNames.name(butcher) + " -> " + HolderNames.name(armorer)),
            "another pair of types is logged once more");
        String report = new SystemReport().toLineSeparatedString();
        assertTrue(report.contains("GA2 worldgen warnings") && report.contains("POI data mismatches: 4 [" + pair + " x3"),
            "a crash report lists every mismatch since the server started:\n" + report);
    }

    @Test
    void hiddenByDefault() {
        assertEquals(RepeatedWarnings.Mode.HIDDEN, RepeatedWarnings.mode(), "worldgenWarnings defaults to hidden");
        assertEquals(RepeatedWarnings.Decision.DEBUG, RepeatedWarnings.decide(RepeatedWarnings.Kind.FAR_WRITE, "test:first"),
            "the first warning of a source goes to the debug log");
        assertEquals(RepeatedWarnings.Decision.COUNT, RepeatedWarnings.decide(RepeatedWarnings.Kind.FAR_WRITE, "test:first"), "its repeats are counted");
    }

    @Test
    void farWritesCountedPerFeature() throws ReflectiveOperationException {
        WorldGenRegion region = region(new ChunkPos(0, 0), () -> "ResourceKey[minecraft:worldgen/placed_feature / test:far]");
        BlockPos far = new BlockPos(100, 64, 0);
        for (int i = 0; i < 3; i++) {
            assertFalse(region.ensureCanWrite(far), "a far chunk is still refused");
        }
        assertTrue(region.ensureCanWrite(new BlockPos(8, 64, 8)), "the centre chunk is writable");
        assertEquals(2, RepeatedWarnings.repeats(RepeatedWarnings.Kind.FAR_WRITE, "test:far"), "the first far write is logged, the two after it counted");
        WorldGenRegion noFeature = region(new ChunkPos(0, 0), null);
        noFeature.ensureCanWrite(far);
        noFeature.ensureCanWrite(far);
        assertEquals(1, RepeatedWarnings.repeats(RepeatedWarnings.Kind.FAR_WRITE, "status minecraft:features"), "without a feature the step's status is the source");
    }

    @Test
    void blockEntityWarningsWrapped() {
        assertTrue(Arrays.stream(WorldGenRegion.class.getDeclaredMethods()).anyMatch(m -> m.getName().endsWith("ga2$missingBlockEntityOnce")),
            "the missing block entity warning is wrapped");
        assertTrue(Arrays.stream(LevelChunk.class.getDeclaredMethods()).anyMatch(m -> m.getName().endsWith("ga2$dummyOnce")),
            "the placeholder block entity warning is wrapped");
        assertTrue(Arrays.stream(LevelChunk.class.getDeclaredMethods()).anyMatch(m -> m.getName().endsWith("ga2$failedOnce")),
            "the failed block entity warning is wrapped");
    }

    @Test
    void generatingNamesShortened() {
        assertEquals("quark:fairy_ring", RepeatedWarnings.generating(() -> "ResourceKey[minecraft:worldgen/placed_feature / quark:fairy_ring]"));
        assertEquals("PlacedFeature[x]", RepeatedWarnings.generating(() -> "PlacedFeature[x]"));
        assertEquals(null, RepeatedWarnings.generating(null));
    }

    /** A region with only what {@code ensureCanWrite} reads: its centre chunk, its step and the feature it generates. */
    private static WorldGenRegion region(ChunkPos center, Supplier<String> generating) throws ReflectiveOperationException {
        Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        WorldGenRegion region = (WorldGenRegion) ((Unsafe) theUnsafe.get(null)).allocateInstance(WorldGenRegion.class);
        ProtoChunk chunk = new ProtoChunk(center, UpgradeData.EMPTY, LevelHeightAccessor.create(-64, 384), HarnessWorld.registries().biomes, null);
        set(region, "center", chunk);
        set(region, "generatingStatus", ChunkStatus.FEATURES);
        set(region, "currentlyGenerating", generating);
        return region;
    }

    private static void set(WorldGenRegion region, String name, Object value) throws ReflectiveOperationException {
        Field f = WorldGenRegion.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(region, value);
    }
}
