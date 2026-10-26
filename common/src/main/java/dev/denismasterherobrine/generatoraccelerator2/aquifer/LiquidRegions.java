package dev.denismasterherobrine.generatoraccelerator2.aquifer;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.compat.MixinTargets;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.function.Function;
import javax.annotation.Nullable;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.RandomState;
import org.slf4j.Logger;

/**
 * YUNG's Better Caves' liquid regions, applied to GA2's own aquifer answers.
 *
 * <p>The mod injects at the return of {@code NoiseBasedAquifer.computeSubstance}. When the aquifer was made with the mod's
 * regions (every chunk's fill: the mod reads them off the random state), a block the aquifer did not leave solid is replaced
 * below the chunk's liquid altitude: by the column's liquid block, unless the block is already of that block, or by nothing
 * (solid) where the column has no liquid. The regions are the mod's own per-chunk data, made from the chunk position alone and
 * cached by the mod, so asking for them earlier or later gives the same table. GA2 reads them through the mod's own objects by
 * reflection (no dependency) and applies the step wherever it answers the aquifer itself: the fast path, the whole-cell air
 * test, the carvers' aquifer of a GPU chunk and the GPU's block kernels, which get each chunk's table with its batch. The CPU
 * fast path only answers where the mod's handler would run after it, and leaves the vanilla fall-back path, where the mod's
 * handler runs itself, alone.
 *
 * <p>{@link #testTables} lets the tests run the step without the mod.
 */
public final class LiquidRegions {
    private LiquidRegions() {}

    private static final Logger LOGGER = Ga2Log.get("Aquifer");
    private static final String REGIONS_CLASS = "com.yungnickyoung.minecraft.bettercaves.worldgen.liquidregion.LiquidRegions";
    private static final String DUCK_METHOD = "bettercaves$getLiquidRegions";

    /** A chunk's liquid blocks by column ({@code (x & 15) * 16 + (z & 15)}, null for none) below and at {@code altitude}. */
    public record Table(int altitude, BlockState[] blocks) {}

    /** Tests: tables for chunks without the mod. Every aquifer and random state then counts as made with regions. */
    public static volatile Function<ChunkPos, Table> testTables;
    private static final Object TEST = new Object();

    /** Ints per chunk the GPU's kernels read: the altitude (or {@link #GPU_NONE}), then an entry per column. */
    public static final int GPU_STRIDE = 257;
    public static final int GPU_NONE = Integer.MIN_VALUE;
    /** The entry's block id for a column without liquid (the block becomes solid). */
    public static final int GPU_NULL = 0xff;
    /** Entry bits: the liquid is of the same block as the aquifer's water, lava or air (the aquifer's own state is then kept). */
    public static final int GPU_SAME_WATER = 256;
    public static final int GPU_SAME_LAVA = 512;
    public static final int GPU_SAME_AIR = 1024;

    /** True when the step applies in this run: the mod's aquifer change is in place, or a test set tables. */
    public static boolean active() {
        return testTables != null || MixinTargets.aquiferLiquidRegions();
    }

    private static volatile boolean resolved;
    private static MethodHandle aquiferField;
    private static MethodHandle stateMethod;
    private static MethodHandle getOrCreate;
    private static MethodHandle altitudeOf;
    private static MethodHandle blocksOf;

    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        try {
            Class<?> regions = Class.forName(REGIONS_CLASS, false, LiquidRegions.class.getClassLoader());
            Field field = null;
            for (Field f : Aquifer.NoiseBasedAquifer.class.getDeclaredFields()) {
                if (f.getType() == regions) {
                    field = f;
                }
            }
            if (field == null) {
                throw new NoSuchFieldException("no field of " + REGIONS_CLASS + " in NoiseBasedAquifer");
            }
            field.setAccessible(true);
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            aquiferField = lookup.unreflectGetter(field).asType(MethodType.methodType(Object.class, Aquifer.class));
            stateMethod = MethodHandles.publicLookup().unreflect(RandomState.class.getMethod(DUCK_METHOD))
                .asType(MethodType.methodType(Object.class, RandomState.class));
            getOrCreate = MethodHandles.publicLookup().unreflect(regions.getMethod("getOrCreateLiquidBlocksForChunk", ChunkPos.class))
                .asType(MethodType.methodType(Object.class, Object.class, ChunkPos.class));
            Class<?> data = Class.forName(REGIONS_CLASS + "$CacheData", false, LiquidRegions.class.getClassLoader());
            altitudeOf = MethodHandles.publicLookup().unreflect(data.getMethod("liquidAltitude")).asType(MethodType.methodType(int.class, Object.class));
            blocksOf = MethodHandles.publicLookup().unreflect(data.getMethod("liquidBlocks")).asType(MethodType.methodType(BlockState[][].class, Object.class));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warn("Better Caves' liquid regions not readable ({}) -> the aquifer keeps vanilla's code", e.toString());
            aquiferField = null;
        } finally {
            resolved = true;
        }
    }

    /** True once the mod's objects were found; false turns the aquifer fast path and the GPU's aquifer off for the run. */
    public static boolean usable() {
        if (testTables != null) {
            return true;
        }
        resolve();
        return aquiferField != null;
    }

    /** The regions {@code aquifer} was made with, or null (the mod's handler then changes nothing for it). */
    @Nullable
    public static Object regionsOf(Aquifer aquifer) {
        if (testTables != null) {
            return TEST;
        }
        resolve();
        if (aquiferField == null) {
            return null;
        }
        try {
            return aquiferField.invokeExact(aquifer);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** The regions a chunk's aquifer would be made with from {@code state}, or null. */
    @Nullable
    public static Object regionsOf(RandomState state) {
        if (testTables != null) {
            return TEST;
        }
        resolve();
        if (stateMethod == null) {
            return null;
        }
        try {
            return stateMethod.invokeExact(state);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** The chunk's table from {@code regions} ({@link #regionsOf}), or null without regions or data. */
    @Nullable
    public static Table table(@Nullable Object regions, int chunkX, int chunkZ) {
        if (regions == null) {
            return null;
        }
        if (regions == TEST) {
            Function<ChunkPos, Table> tables = testTables;
            return tables == null ? null : tables.apply(new ChunkPos(chunkX, chunkZ));
        }
        try {
            Object data = getOrCreate.invokeExact(regions, new ChunkPos(chunkX, chunkZ));
            if (data == null) {
                return null; // the mod logs a warning and changes nothing
            }
            BlockState[][] columns = (BlockState[][]) blocksOf.invokeExact(data);
            BlockState[] blocks = new BlockState[256];
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    blocks[x * 16 + z] = columns[x][z];
                }
            }
            return new Table((int) altitudeOf.invokeExact(data), blocks);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** The mod's step on the aquifer's answer {@code state} (null for solid) at the block. */
    @Nullable
    public static BlockState apply(Table table, int x, int y, int z, @Nullable BlockState state) {
        if (state == null || y > table.altitude) {
            return state;
        }
        BlockState liquid = table.blocks[(x & 15) * 16 + (z & 15)];
        if (liquid == null) {
            return null;
        }
        return state.is(liquid.getBlock()) ? state : liquid;
    }

    /**
     * The table as the GPU's block kernels read it ({@link #GPU_STRIDE} ints), over the program's state table whose ids 0, 2 and 3
     * are air, the aquifer's fluid and lava. Null when a liquid is a state the program has no id for (the chunk goes to the CPU).
     */
    @Nullable
    public static int[] gpu(Table table, BlockState[] states) {
        int[] out = new int[GPU_STRIDE];
        out[0] = table.altitude;
        for (int col = 0; col < 256; col++) {
            BlockState liquid = table.blocks[col];
            if (liquid == null) {
                out[1 + col] = GPU_NULL;
                continue;
            }
            int id = -1;
            for (int i = 0; i < states.length; i++) {
                if (states[i] == liquid) {
                    id = i;
                    break;
                }
            }
            if (id < 0 || id >= GPU_NULL) {
                return null;
            }
            int e = id;
            if (states[0].is(liquid.getBlock())) {
                e |= GPU_SAME_AIR;
            }
            if (states[2].is(liquid.getBlock())) {
                e |= GPU_SAME_WATER;
            }
            if (states[3].is(liquid.getBlock())) {
                e |= GPU_SAME_LAVA;
            }
            out[1 + col] = e;
        }
        return out;
    }
}
