package dev.denismasterherobrine.generatoraccelerator2.compat.yungscavebiomes;

import com.google.common.collect.MapMaker;
import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.biome.ClimateSearch;
import dev.denismasterherobrine.generatoraccelerator2.biome.SearchTies;
import dev.denismasterherobrine.generatoraccelerator2.compat.CodeShape;
import dev.denismasterherobrine.generatoraccelerator2.compat.Reflect;
import dev.denismasterherobrine.generatoraccelerator2.compat.Takeover;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/**
 * YUNG's Cave Biomes' Frosted Caves water, done by GA2 in place of the mod's handler ({@link YungsCaveBiomesMixins#AQUIFER}, taken over).
 *
 * <p>The mod's handler at the return of {@code computeFluid} looks up the biome at the status's cell centre: the generator's biome
 * source with the chunk's climate sampler, both noted on the NoiseChunk at the biome step ({@code NoiseSamplerBiomeHolder}). In its
 * Frosted Caves, the status keeps its level with water. Without a biome registry noted, nothing changes. {@link #apply} does exactly
 * that. That lookup goes through the climate search's per-thread last result, so a status computed in another order could get
 * another biome on a tie. GA2's aquifer therefore computed every status in vanilla's order (search only).
 *
 * <p>Now each aquifer looks the biome up for all its cells first ({@link #cells}), quietly (the thread's last results stay as
 * they were) and watched for ties. When every answer had a single nearest leaf, it holds in any order: the aquifer keeps its
 * faster modes, and each status takes its cell's answer. Otherwise that aquifer computes its statuses in vanilla's order with live
 * lookups, as before.
 *
 * <p>The GPU's aquifer has no biome step. A chunk keeps the GPU's answer when the step would change none of its cells
 * ({@link #holdsWithout}). Otherwise the CPU fills it.
 */
public final class FrostedCaves {
    private FrostedCaves() {}

    private static final String HOLDER = "com.yungnickyoung.minecraft.yungscavebiomes.world.NoiseSamplerBiomeHolder";
    private static final String MODULE = "com.yungnickyoung.minecraft.yungscavebiomes.module.BiomeModule";
    /** The mod's handler, as written against (YUNG's Cave Biomes 4.1.1 for 26.1.2). */
    private static final String HANDLER = "yungscavebiomes_noLavaAquifersInFrostedCaves";
    private static final String HANDLER_CODE = "3403e9103b07d7f7";

    /** An aquifer without the step: the chunk's NoiseChunk has no biome registry noted. */
    public static final byte[] NO_STEP = new byte[0];

    /**
     * Sure answers by cell centre, per biome source: a cell is shared by the aquifers of up to nine chunks. A fixed table, a newer
     * answer replacing an older one in its slot. Only answers that had a single nearest leaf in every search go in.
     */
    private record Answer(long key, boolean frosted) {}

    private static final int TABLE_SIZE = 1 << 16;
    private static final ConcurrentMap<BiomeSource, Answer[]> TABLES = new MapMaker().weakKeys().makeMap();

    /** Aquifers whose cells were all looked up for sure, and those left to vanilla's order. */
    public static final LongAdder SURE = new LongAdder();
    public static final LongAdder UNSURE = new LongAdder();
    /** Aquifers whose NoiseChunk had no biome registry noted: no step, as with the mod. */
    public static final LongAdder NO_REGISTRY = new LongAdder();

    @Nullable
    private static MethodHandle biomeSource;
    @Nullable
    private static MethodHandle biomeRegistry;
    @Nullable
    private static MethodHandle climateSampler;
    @Nullable
    private static ResourceKey<Biome> frostedCaves;
    private static volatile boolean active;

    /**
     * Sets the step up where the takeover is on ({@link Takeover#willCancel}). Mod construction, before any aquifer. The mod's handler
     * is cancelled only when the aquifer class loads, and only once this has read the mod's classes. Otherwise the handler stays.
     */
    @SuppressWarnings("unchecked")
    public static void register() {
        if (!Takeover.willCancel(YungsCaveBiomesMixins.AQUIFER)) {
            return;
        }
        String code = CodeShape.fingerprint(CodeShape.method(CodeShape.classNode(YungsCaveBiomesMixins.AQUIFER), HANDLER, null));
        if (!code.equals(HANDLER_CODE)) {
            Ga2Log.get("Compat").info("YUNG's Cave Biomes Frosted Caves: unknown handler code ({}) -> the mod's own handler kept", code);
            return;
        }
        Class<?> holder = Reflect.type(HOLDER);
        Class<?> module = Reflect.type(MODULE);
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            MethodType object = MethodType.methodType(Object.class, Object.class);
            biomeSource = lookup.findVirtual(holder, "getBiomeSource", MethodType.methodType(BiomeSource.class)).asType(object);
            biomeRegistry = lookup.findVirtual(holder, "getBiomeRegistry", MethodType.methodType(Registry.class)).asType(object);
            climateSampler = lookup.findVirtual(holder, "getClimateSampler", MethodType.methodType(Climate.Sampler.class)).asType(object);
            frostedCaves = (ResourceKey<Biome>) module.getField("FROSTED_CAVES").get(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // the mod's handler stays, so its water still comes from it
            Ga2Log.get("Compat").warn("YUNG's Cave Biomes Frosted Caves: not readable ({}) -> the mod's own handler kept", e.toString());
            return;
        }
        active = true;
        // from now on the takeover cancels the mod's handler when the aquifer class loads
        YungsCaveBiomesMixins.replaceFrosted();
        Ga2Log.get("Compat").info("YUNG's Cave Biomes Frosted Caves water -> done by GA2, aquifer kept in its faster modes where the biomes are sure");
    }

    /**
     * Notes on {@code to} what the mod noted on {@code from} at the biome step, for a NoiseChunk made again for the same chunk (the
     * carvers' check against vanilla). Without it the new NoiseChunk's aquifer has no step.
     */
    public static void copyStep(Object from, Object to) {
        if (!active || from == null || to == null) {
            return;
        }
        try {
            for (String property : new String[] { "BiomeSource", "BiomeRegistry", "ClimateSampler", "WorldSeed" }) {
                Method get = from.getClass().getMethod("get" + property);
                Method set = to.getClass().getMethod("set" + property, get.getReturnType());
                set.invoke(to, get.invoke(from));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Ga2Log.get("Verify").warn("YUNG's Cave Biomes biome holder not copied ({})", e.toString());
        }
    }

    /** Whether GA2 does the step (the mod's handler was taken over). */
    public static boolean active() {
        return active;
    }

    /**
     * The mod's handler: {@code status} with water where the cell centre at {@code (x, y, z)} lies in Frosted Caves. {@code known}
     * is the cell's answer looked up ahead (1 frosted, 0 not), or -1 to look it up now.
     */
    public static Aquifer.FluidStatus apply(Object noiseChunk, int x, int y, int z, Aquifer.FluidStatus status, int known) {
        boolean frosted;
        if (known >= 0) {
            frosted = known == 1;
        } else {
            Object registry = invoke(biomeRegistry, noiseChunk);
            if (registry == null) {
                return status;
            }
            frosted = frosted((BiomeSource) invoke(biomeSource, noiseChunk), (Registry<?>) registry, (Climate.Sampler) invoke(climateSampler, noiseChunk), x, y, z);
        }
        return frosted ? new Aquifer.FluidStatus(status.fluidLevel(), Blocks.WATER.defaultBlockState()) : status;
    }

    /**
     * Every cell's answer of an aquifer, looked up quietly and watched for ties: {@link #NO_STEP} without the step, null when an answer
     * depends on the order of the lookups (the aquifer then keeps vanilla's order). Index {@code (gy * sizeZ + gz) * sizeX + gx}, as
     * vanilla's {@code getIndex}. {@code locations} receives each cell's centre, as vanilla's cell random places it.
     */
    @Nullable
    public static byte[] cells(Object noiseChunk, PositionalRandomFactory random, int minGridX, int minGridY, int minGridZ, int sizeX, int sizeY,
                               int sizeZ, long[] locations) {
        Object registry = invoke(biomeRegistry, noiseChunk);
        if (registry == null) {
            NO_REGISTRY.increment();
            return NO_STEP;
        }
        if (!ClimateSearch.enabled()) {
            // vanilla's tree walk would move the thread's last results
            UNSURE.increment();
            return null;
        }
        BiomeSource source = (BiomeSource) invoke(biomeSource, noiseChunk);
        Climate.Sampler sampler = (Climate.Sampler) invoke(climateSampler, noiseChunk);
        Answer[] table = TABLES.computeIfAbsent(source, k -> new Answer[TABLE_SIZE]);
        byte[] bits = new byte[sizeX * sizeY * sizeZ];
        boolean sure = ClimateSearch.quietly(() -> {
            int i = 0;
            for (int gy = 0; gy < sizeY; gy++) {
                for (int gz = 0; gz < sizeZ; gz++) {
                    for (int gx = 0; gx < sizeX; gx++, i++) {
                        int cx = minGridX + gx, cy = minGridY + gy, cz = minGridZ + gz;
                        RandomSource r = random.at(cx, cy, cz);
                        int x = cx * 16 + r.nextInt(10), y = cy * 12 + r.nextInt(9), z = cz * 16 + r.nextInt(10);
                        long key = BlockPos.asLong(x, y, z);
                        locations[i] = key;
                        Answer a = table[slot(key)];
                        if (a != null && a.key == key) {
                            bits[i] = a.frosted ? (byte) 1 : (byte) 0;
                            continue;
                        }
                        Boolean frosted = sureLookup(source, (Registry<?>) registry, sampler, x, y, z);
                        if (frosted == null) {
                            return false;
                        }
                        table[slot(key)] = new Answer(key, frosted);
                        bits[i] = frosted ? (byte) 1 : (byte) 0;
                    }
                }
            }
            return true;
        });
        if (!sure) {
            UNSURE.increment();
            return null;
        }
        SURE.increment();
        return bits;
    }

    /** GPU answers that stood with the step, and those given back to the CPU (a cell to change, or a tie). */
    public static final LongAdder GPU_KEPT = new LongAdder();
    public static final LongAdder GPU_BACK = new LongAdder();

    /** One cell of an aquifer computed elsewhere (the GPU): its centre, and whether its status's fluid is water. */
    public interface Cells {
        int count();

        long centre(int cell);

        boolean water(int cell);
    }

    /**
     * Whether an aquifer computed without the step (the GPU's) is the one the step gives. The step only turns a status's fluid to
     * water, so it holds when no cell whose fluid is not water has its centre in Frosted Caves. Each such cell is looked up as in
     * {@link #cells}: quietly, watched for ties, through the table of sure answers. False on a cell to change or a tie. The CPU then
     * computes the chunk's aquifer with the step.
     */
    public static boolean holdsWithout(Object noiseChunk, Cells cells) {
        Object registry = noiseChunk != null ? invoke(biomeRegistry, noiseChunk) : null;
        if (registry == null) {
            // no step for this chunk, as with the mod
            GPU_KEPT.increment();
            return true;
        }
        if (!ClimateSearch.enabled()) {
            GPU_BACK.increment();
            return false;
        }
        BiomeSource source = (BiomeSource) invoke(biomeSource, noiseChunk);
        Climate.Sampler sampler = (Climate.Sampler) invoke(climateSampler, noiseChunk);
        Answer[] table = TABLES.computeIfAbsent(source, k -> new Answer[TABLE_SIZE]);
        boolean holds = ClimateSearch.quietly(() -> {
            for (int i = 0; i < cells.count(); i++) {
                if (cells.water(i)) {
                    continue;
                }
                long key = cells.centre(i);
                Answer a = table[slot(key)];
                Boolean frosted;
                if (a != null && a.key == key) {
                    frosted = a.frosted;
                } else {
                    frosted = sureLookup(source, (Registry<?>) registry, sampler, BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key));
                    if (frosted == null) {
                        return false;
                    }
                    table[slot(key)] = new Answer(key, frosted);
                }
                if (frosted) {
                    return false;
                }
            }
            return true;
        });
        (holds ? GPU_KEPT : GPU_BACK).increment();
        return holds;
    }

    private static int slot(long key) {
        long hash = key * 0x9E3779B97F4A7C15L;
        return (int) (hash ^ hash >>> 32) & (TABLE_SIZE - 1);
    }

    /** The mod's test at a cell centre, watched for ties: null when a search had another leaf as near as its answer. */
    @Nullable
    private static Boolean sureLookup(BiomeSource source, Registry<?> registry, Climate.Sampler sampler, int x, int y, int z) {
        SearchTies.Watch watch = SearchTies.start();
        if (watch == null) {
            return null;
        }
        boolean frosted;
        boolean sure;
        try {
            frosted = frosted(source, registry, sampler, x, y, z);
        } finally {
            sure = SearchTies.end(watch);
        }
        return sure ? frosted : null;
    }

    /** The mod's test: the biome at the quart of {@code (x, y, z)} is Frosted Caves by its registry key. */
    private static boolean frosted(BiomeSource source, Registry<?> registry, Climate.Sampler sampler, int x, int y, int z) {
        Holder<Biome> biome = source.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z), sampler);
        @SuppressWarnings("unchecked")
        Optional<ResourceKey<Biome>> key = ((Registry<Biome>) registry).getResourceKey(biome.value());
        return key.isPresent() && key.get() == frostedCaves;
    }

    private static Object invoke(MethodHandle h, Object noiseChunk) {
        try {
            return h.invokeExact(noiseChunk);
        } catch (Throwable t) {
            throw Reflect.unchecked(t);
        }
    }
}
