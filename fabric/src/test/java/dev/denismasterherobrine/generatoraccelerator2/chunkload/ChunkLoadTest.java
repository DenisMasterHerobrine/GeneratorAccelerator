package dev.denismasterherobrine.generatoraccelerator2.chunkload;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.entity.ChunkEntities;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/** Chunk loading and sending: section change counts, block data written ahead, the entity budget. */
class ChunkLoadTest {
    private static PalettedContainerFactory containers() {
        return TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1L).registries.containers;
    }

    /** The chunk loading mixins' injections are required. A target class whose code they do not find fails to load. */
    @Test
    void targetsLoad() throws ClassNotFoundException {
        ClassLoader loader = getClass().getClassLoader();
        for (String name : List.of("net.minecraft.world.level.chunk.storage.SerializableChunkData", "net.minecraft.server.network.PlayerChunkSender",
            "net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData", "net.minecraft.server.level.ServerLevel",
            "net.minecraft.server.level.ServerChunkCache$MainThreadExecutor", "net.minecraft.server.level.DistanceManager$PlayerTicketTracker",
            "net.minecraft.server.level.DistanceManager", "net.minecraft.server.level.ThrottlingChunkTaskDispatcher", "net.minecraft.server.level.ChunkMap",
            "net.minecraft.world.level.entity.PersistentEntitySectionManager", "net.minecraft.world.level.chunk.LevelChunkSection")) {
            Class.forName(name, false, loader);
        }
    }

    @Test
    void sectionCountsBlockChanges() {
        LevelChunkSection section = new LevelChunkSection(containers());
        LevelChunkSection[] sections = { section };
        long before = ChunkPackets.version(sections);
        section.setBlockState(1, 2, 3, Blocks.STONE.defaultBlockState());
        assertTrue(ChunkPackets.version(sections) > before);
    }

    /** Sections with a few states, many states and none, as single, linear, hash map and global palettes. */
    private static LevelChunkSection[] randomChunk(PalettedContainerFactory containers, Random random) {
        List<BlockState> states = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            states.add(block.defaultBlockState());
        }
        LevelChunkSection[] sections = new LevelChunkSection[24];
        for (int s = 0; s < sections.length; s++) {
            LevelChunkSection section = new LevelChunkSection(containers);
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
        PalettedContainerFactory containers = containers();
        Random random = new Random(42);
        List<LevelChunkSection[]> chunks = new ArrayList<>();
        for (int c = 0; c < 24; c++) {
            chunks.add(randomChunk(containers, random));
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

    /**
     * An entity in a chunk, made without its constructor. Fabric API's load event takes every added entity for an {@link Entity}
     * and reads its level, a server level made the same way.
     */
    private static final class FakeEntity extends Entity {
        private static int ids;
        private static ServerLevel level;
        UUID uuid;
        ChunkPos chunk;

        @SuppressWarnings("unused")
        private FakeEntity(EntityType<?> type, Level level) {
            super(type, level);
        }

        static FakeEntity in(ChunkPos chunk) {
            try {
                Field f = Unsafe.class.getDeclaredField("theUnsafe");
                f.setAccessible(true);
                Unsafe unsafe = (Unsafe) f.get(null);
                if (level == null) {
                    level = (ServerLevel) unsafe.allocateInstance(ServerLevel.class);
                }
                FakeEntity e = (FakeEntity) unsafe.allocateInstance(FakeEntity.class);
                e.chunk = chunk;
                e.uuid = UUID.randomUUID();
                e.setId(++ids);
                e.setBoundingBox(new AABB(e.blockPosition()));
                return e;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override public UUID getUUID() { return this.uuid; }
        @Override public BlockPos blockPosition() { return this.chunk.getBlockAt(8, 64, 8); }
        @Override public Level level() { return level; }
        @Override public void setLevelCallback(EntityInLevelCallback callback) {}
        @Override public Stream<Entity> getSelfAndPassengers() { return Stream.of(this); }
        @Override public Stream<Entity> getPassengersAndSelf() { return Stream.of(this); }
        @Override public boolean shouldBeSaved() { return true; }
        @Override public boolean isAlwaysTicking() { return false; }
        @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {}
        @Override public boolean hurtServer(ServerLevel level, DamageSource source, float amount) { return false; }
        @Override protected void readAdditionalSaveData(ValueInput input) {}
        @Override protected void addAdditionalSaveData(ValueOutput output) {}
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
                FakeEntity e = FakeEntity.in(pos);
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
            assertTrue(manager.areEntitiesLoaded(new ChunkPos(i, 0).pack()));
        }
    }

}
