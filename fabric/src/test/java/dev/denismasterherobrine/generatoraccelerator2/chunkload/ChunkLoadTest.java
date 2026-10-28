package dev.denismasterherobrine.generatoraccelerator2.chunkload;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload.SectionStorageAccessor;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import io.netty.buffer.Unpooled;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.entity.ChunkEntities;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

/** Chunk loading and sending: section change counts, block data written ahead, the entity budget, POI reads ahead. */
class ChunkLoadTest {
    private static Registry<Biome> biomes() {
        return TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1L).registries.biomes;
    }

    /** The chunk loading mixins' injections are required. A target class whose code they do not find fails to load. */
    @Test
    void targetsLoad() throws ClassNotFoundException {
        ClassLoader loader = getClass().getClassLoader();
        for (String name : List.of("net.minecraft.world.level.chunk.storage.ChunkSerializer",
            "net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData", "net.minecraft.server.level.ServerLevel",
            "net.minecraft.server.level.ServerChunkCache$MainThreadExecutor", "net.minecraft.server.level.DistanceManager$PlayerTicketTracker",
            "net.minecraft.server.level.DistanceManager", "net.minecraft.world.level.chunk.storage.SectionStorage", "net.minecraft.server.level.ChunkMap",
            "net.minecraft.world.level.entity.PersistentEntitySectionManager", "net.minecraft.world.level.chunk.LevelChunkSection")) {
            Class.forName(name, false, loader);
        }
    }

    @Test
    void sectionCountsBlockChanges() {
        LevelChunkSection section = new LevelChunkSection(biomes());
        LevelChunkSection[] sections = { section };
        long before = ChunkPackets.version(sections);
        section.setBlockState(1, 2, 3, Blocks.STONE.defaultBlockState());
        assertTrue(ChunkPackets.version(sections) > before);
    }

    /** Sections with a few states, many states and none, as single, linear, hash map and global palettes. */
    private static LevelChunkSection[] randomChunk(Registry<Biome> biomes, Random random) {
        List<BlockState> states = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            states.add(block.defaultBlockState());
        }
        LevelChunkSection[] sections = new LevelChunkSection[24];
        for (int s = 0; s < sections.length; s++) {
            LevelChunkSection section = new LevelChunkSection(biomes);
            int kinds = switch (s % 4) {
                case 0 -> 0;
                case 1 -> 3;
                case 2 -> 40;
                default -> 400;
            };
            if (kinds > 0) {
                for (int i = 0; i < 4096; i++) {
                    section.setBlockState(i & 15, (i >> 8) & 15, (i >> 4) & 15, states.get(random.nextInt(kinds)), false);
                }
            }
            sections[s] = section;
        }
        return sections;
    }

    @Test
    void parallelWritesMatchTheSectionWrites() {
        Registry<Biome> biomes = biomes();
        Random random = new Random(42);
        List<LevelChunkSection[]> chunks = new ArrayList<>();
        for (int c = 0; c < 24; c++) {
            chunks.add(randomChunk(biomes, random));
        }
        byte[][] parallel = new byte[chunks.size()][];
        assertTrue(ChunkPackets.forEachParallel(chunks.size(), i -> parallel[i] = ChunkPackets.encode(chunks.get(i))));
        for (int c = 0; c < chunks.size(); c++) {
            // what ClientboundLevelChunkPacketData's calculateChunkSize and extractChunkData make
            int size = 0;
            for (LevelChunkSection section : chunks.get(c)) {
                size += section.getSerializedSize();
            }
            byte[] vanilla = new byte[size];
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(vanilla));
            buf.writerIndex(0);
            for (LevelChunkSection section : chunks.get(c)) {
                section.write(buf);
            }
            assertArrayEquals(vanilla, parallel[c], "chunk " + c);
        }
    }

    private static final class FakeEntity implements EntityAccess {
        private static int ids;
        final int id = ++ids;
        final UUID uuid = UUID.randomUUID();
        final ChunkPos chunk;

        FakeEntity(ChunkPos chunk) {
            this.chunk = chunk;
        }

        @Override public int getId() { return this.id; }
        @Override public UUID getUUID() { return this.uuid; }
        @Override public BlockPos blockPosition() { return this.chunk.getBlockAt(8, 64, 8); }
        @Override public AABB getBoundingBox() { return new AABB(blockPosition()); }
        @Override public void setLevelCallback(EntityInLevelCallback callback) {}
        @Override public Stream<? extends EntityAccess> getSelfAndPassengers() { return Stream.of(this); }
        @Override public Stream<? extends EntityAccess> getPassengersAndSelf() { return Stream.of(this); }
        @Override public void setRemoved(Entity.RemovalReason reason) {}
        @Override public boolean shouldBeSaved() { return true; }
        @Override public boolean isAlwaysTicking() { return false; }
    }

    /** Each entity takes 2 ms to start tracking. With the 5 ms budget a tick adds a few chunks, and the rest follow in order. */
    @Test
    void entityBudgetSpreadsChunksOverTicks() {
        List<FakeEntity> tracked = new ArrayList<>();
        LevelCallback<FakeEntity> callback = new LevelCallback<>() {
            @Override public void onCreated(FakeEntity e) {}
            @Override public void onDestroyed(FakeEntity e) {}
            @Override public void onTickingStart(FakeEntity e) {}
            @Override public void onTickingEnd(FakeEntity e) {}
            @Override public void onSectionChange(FakeEntity e) {}
            @Override public void onTrackingEnd(FakeEntity e) {}

            @Override
            public void onTrackingStart(FakeEntity e) {
                tracked.add(e);
                try {
                    Thread.sleep(2);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        List<FakeEntity> saved = new ArrayList<>();
        EntityPersistentStorage<FakeEntity> storage = new EntityPersistentStorage<>() {
            @Override
            public CompletableFuture<ChunkEntities<FakeEntity>> loadEntities(ChunkPos pos) {
                FakeEntity e = new FakeEntity(pos);
                saved.add(e);
                return CompletableFuture.completedFuture(new ChunkEntities<>(pos, List.of(e)));
            }

            @Override public void storeEntities(ChunkEntities<FakeEntity> entities) {}
            @Override public void flush(boolean sync) {}
        };
        PersistentEntitySectionManager<FakeEntity> manager = new PersistentEntitySectionManager<>(FakeEntity.class, callback, storage);
        int chunks = 10;
        for (int i = 0; i < chunks; i++) {
            manager.updateChunkStatus(new ChunkPos(i, 0), Visibility.TRACKED);
        }
        manager.tick();
        int first = tracked.size();
        assertTrue(first >= 1 && first < chunks, "first tick added " + first);
        for (int tick = 0; tick < 50 && tracked.size() < chunks; tick++) {
            manager.tick();
        }
        assertEquals(saved, tracked);
        for (int i = 0; i < chunks; i++) {
            assertTrue(manager.areEntitiesLoaded(new ChunkPos(i, 0).toLong()));
        }
    }

    @Test
    void poiColumnTakesTheReadMadeAhead() throws Exception {
        Path dir = Files.createTempDirectory("ga2-poi");
        LevelHeightAccessor heights = LevelHeightAccessor.create(-64, 384);
        RegistryAccess access = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        BlockPos bed = new BlockPos(5, 70, 7);
        ChunkPos pos = new ChunkPos(bed);
        Holder<PoiType> home = BuiltInRegistries.POINT_OF_INTEREST_TYPE.getHolderOrThrow(PoiTypes.HOME);
        try (PoiManager first = new PoiManager(dir, DataFixers.getDataFixer(), false, access, heights)) {
            first.add(bed, home);
            first.flush(pos);
        }
        try (PoiManager second = new PoiManager(dir, DataFixers.getDataFixer(), false, access, heights)) {
            CompoundTag chunkTag = new CompoundTag();
            long used = ChunkLoadStats.POI_PREFETCH_USED.sum();
            long reads = ChunkLoadStats.POI_READS.sum();
            long waits = ChunkLoadStats.POI_WAIT.count();
            PoiPrefetch.start(second, ((SectionStorageAccessor) second).ga2$worker(), pos, chunkTag).join();
            // another tag's load does not get the read
            PoiPrefetch.enter(second, pos, new CompoundTag());
            PoiPrefetch.exit();
            PoiPrefetch.start(second, ((SectionStorageAccessor) second).ga2$worker(), pos, chunkTag).join();
            PoiPrefetch.enter(second, pos, chunkTag);
            Optional<Holder<PoiType>> type;
            try {
                type = second.getType(bed);
            } finally {
                PoiPrefetch.exit();
            }
            assertEquals(home, type.orElseThrow());
            assertEquals(used + 1, ChunkLoadStats.POI_PREFETCH_USED.sum());
            assertEquals(reads + 1, ChunkLoadStats.POI_READS.sum());
            assertEquals(waits, ChunkLoadStats.POI_WAIT.count());
        }
    }
}
