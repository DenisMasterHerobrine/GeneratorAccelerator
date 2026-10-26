package dev.denismasterherobrine.generatoraccelerator2.compat.zeta;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.chunkio.VerifyCounter;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.Reflect;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Random;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * Zeta's cluster generators ({@code ClusterBasedGenerator}, used for Quark's big stone clusters). For every chunk a generator
 * decorates, each cluster source in the 5x5 chunks around that chunk runs {@code generateChunkPart}. That method walks every block
 * of the chunk over the cluster's height and asks {@code context.canPlaceAt(pos) && shape.isInside(pos)}. Quark's
 * {@code canPlaceAt} reads the block and runs its replace predicate, a chain of ten {@code BiPredicate.or}s. {@code isInside} is
 * false wherever {@code dx * dx + dy * dy + dz * dz > 1}, where each offset from the source is divided by the radius on its axis.
 * For the many sources whose cluster cannot reach the chunk, that holds everywhere.
 *
 * <p>This class visits the same blocks in the same order, but only within the cluster's box: {@code |offset| <= |radius|} on x
 * and z. Zeta's own y range already lies within the box. A block whose sum of squares is over 1 skips both calls. Both calls are
 * free of side effects: Quark's {@code canPlaceAt} reads the block and asks the predicate, and {@code isInside} only computes. So
 * leaving them out where {@code isInside} is false changes nothing. Every block that {@code consume} gets is the one Zeta's loop
 * gives it, in Zeta's order. Applies only to Quark's {@code BigStoneClusterGenerator} itself. It also requires that every method
 * this class relies on has the code it was written against ({@link ZetaMixins#codeKnown}, by fingerprint), and that no other mod
 * changes those methods (hook {@code zeta.clusters}).
 */
public final class ZetaClusters {
    private static final Logger LOGGER = Ga2Log.get("Compat");

    private static final HookTargets.Hook HOOK = HookTargets.hook(HookTargets.ZETA_CLUSTERS);

    /** Cluster parts walked here, those whose cluster cannot reach the chunk, and blocks asked {@code canPlaceAt}. */
    public static final LongAdder PARTS = new LongAdder(), OUT_OF_REACH = new LongAdder(), ASKED = new LongAdder();
    /**
     * {@code -Dgeneratoraccelerator2.verify.zetaClusters=N}: every N-th part also runs Zeta's whole loop without placing anything.
     * The blocks that Zeta's loop would place, in its order, are compared with the walk's blocks before they are placed.
     */
    private static final VerifyCounter VERIFY =
        new VerifyCounter("zetaClusters");

    private ZetaClusters() {}

    private static final class Handles {
        static final Class<?> STONE;
        static final MethodHandle SHAPE_PROVIDER, AROUND, CREATE_CONTEXT, LOWER, UPPER, SRC, RADIUS, IS_INSIDE, CAN_PLACE, CONSUME;

        static {
            try {
                ClassLoader loader = ZetaClusters.class.getClassLoader();
                Class<?> based = Class.forName(ZetaMixins.CLUSTER_BASED, false, loader);
                Class<?> shape = Class.forName(ZetaMixins.SHAPE, false, loader);
                Class<?> provider = Class.forName(ZetaMixins.PROVIDER, false, loader);
                Class<?> context = Class.forName(ZetaMixins.CONTEXT, false, loader);
                STONE = Class.forName(ZetaMixins.QUARK_STONE, false, loader);
                // The context class would otherwise load on the first createContext. Load it now, so that its inspection
                // (zeta.clusters) is done before the first part is walked.
                Class.forName(ZetaMixins.QUARK_STONE_CONTEXT, false, loader);
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                MethodType object = MethodType.methodType(Object.class, Object.class);
                SHAPE_PROVIDER = lookup.findGetter(based, "shapeProvider", provider).asType(object);
                AROUND = lookup.findVirtual(provider, "around", MethodType.methodType(shape, BlockPos.class))
                    .asType(MethodType.methodType(Object.class, Object.class, BlockPos.class));
                CREATE_CONTEXT = lookup.findVirtual(based, "createContext",
                        MethodType.methodType(context, BlockPos.class, ChunkGenerator.class, Random.class, BlockPos.class, WorldGenRegion.class))
                    .asType(MethodType.methodType(Object.class, Object.class, BlockPos.class, ChunkGenerator.class, Random.class, BlockPos.class, WorldGenRegion.class));
                LOWER = lookup.findVirtual(shape, "getLowerBound", MethodType.methodType(int.class)).asType(MethodType.methodType(int.class, Object.class));
                UPPER = lookup.findVirtual(shape, "getUpperBound", MethodType.methodType(int.class)).asType(MethodType.methodType(int.class, Object.class));
                SRC = lookup.findVirtual(shape, "src", MethodType.methodType(BlockPos.class)).asType(MethodType.methodType(BlockPos.class, Object.class));
                RADIUS = lookup.findVirtual(shape, "radius", MethodType.methodType(Vec3.class)).asType(MethodType.methodType(Vec3.class, Object.class));
                IS_INSIDE = lookup.findVirtual(shape, "isInside", MethodType.methodType(boolean.class, BlockPos.class))
                    .asType(MethodType.methodType(boolean.class, Object.class, BlockPos.class));
                CAN_PLACE = lookup.findVirtual(context, "canPlaceAt", MethodType.methodType(boolean.class, BlockPos.class))
                    .asType(MethodType.methodType(boolean.class, Object.class, BlockPos.class));
                CONSUME = lookup.findVirtual(context, "consume", MethodType.methodType(void.class, BlockPos.class))
                    .asType(MethodType.methodType(void.class, Object.class, BlockPos.class));
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

    private static volatile boolean broken;

    /**
     * {@code ClusterBasedGenerator.generateChunkPart(src, generator, random, chunkCorner, world)} for {@code self}. Returns false
     * when Zeta's own method is to run: another generator class, the hook off, or the handles not found.
     */
    public static boolean generateChunkPart(Object self, BlockPos src, ChunkGenerator generator, Random random, BlockPos chunkCorner, WorldGenRegion world) {
        if (broken) {
            return false;
        }
        try {
            if (self.getClass() != Handles.STONE || !HOOK.allowed()) {
                return false;
            }
            walk(self, src, generator, random, chunkCorner, world);
            return true;
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            broken = true;
            LOGGER.warn("Zeta cluster generator unreachable -> own loop", e);
            return false;
        } catch (Throwable t) {
            throw Reflect.unchecked(t);
        }
    }

    private static void walk(Object self, BlockPos src, ChunkGenerator generator, Random random, BlockPos chunkCorner, WorldGenRegion world) throws Throwable {
        // generateChunkPart: the shape and the context, as Zeta makes them
        Object shape = Handles.AROUND.invokeExact(Handles.SHAPE_PROVIDER.invokeExact(self), src);
        Object context = Handles.CREATE_CONTEXT.invokeExact(self, src, generator, random, chunkCorner, world);
        // forEachChunkBlock(world, chunkCorner, lower, upper, ...)
        int minY = Math.max(world.getMinBuildHeight() + 1, (int) Handles.LOWER.invokeExact(shape));
        int maxY = Math.min(world.getMaxBuildHeight() - 1, (int) Handles.UPPER.invokeExact(shape));
        int chunkX = chunkCorner.getX();
        int chunkZ = chunkCorner.getZ();
        PARTS.increment();
        BlockPos center = (BlockPos) Handles.SRC.invokeExact(shape);
        Vec3 radius = (Vec3) Handles.RADIUS.invokeExact(shape);
        int sx = center.getX();
        int sy = center.getY();
        int sz = center.getZ();
        // isInside is false where |dx| > 1 on an axis, which means an integer offset over the radius's magnitude.
        // Radii are whole numbers.
        int reachX = (int) Math.floor(Math.abs(radius.x));
        int reachZ = (int) Math.floor(Math.abs(radius.z));
        int x0 = (int) Math.max(0L, (long) sx - reachX - chunkX);
        int x1 = (int) Math.min(15L, (long) sx + reachX - chunkX);
        int z0 = (int) Math.max(0L, (long) sz - reachZ - chunkZ);
        int z1 = (int) Math.min(15L, (long) sz + reachZ - chunkZ);
        boolean verify = VERIFY.sample();
        LongArrayList expected = verify ? zetaLoop(shape, context, chunkX, chunkZ, minY, maxY) : null;
        if (x0 > x1 || z0 > z1 || minY >= maxY) {
            OUT_OF_REACH.increment();
            if (verify) {
                VERIFY.result(expected.isEmpty(), "cluster part at " + center + " out of reach of chunk " + (chunkX >> 4) + "," + (chunkZ >> 4), expected.size() + " blocks", "none");
            }
            return;
        }
        LongArrayList got = verify ? new LongArrayList() : null;
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(0, 0, 0);
        long asked = 0;
        for (int x = x0; x <= x1; x++) {
            int px = chunkX + x;
            double dx = (double) (px - sx) / radius.x;
            for (int y = minY; y < maxY; y++) {
                double dy = (double) (y - sy) / radius.y;
                for (int z = z0; z <= z1; z++) {
                    int pz = chunkZ + z;
                    double dz = (double) (pz - sz) / radius.z;
                    // isInside's first test, computed the same way isInside computes it
                    if (dx * dx + dy * dy + dz * dz > 1.0) {
                        continue;
                    }
                    mutable.set(px, y, pz);
                    asked++;
                    if ((boolean) Handles.CAN_PLACE.invokeExact(context, (BlockPos) mutable) && (boolean) Handles.IS_INSIDE.invokeExact(shape, (BlockPos) mutable)) {
                        if (got != null) {
                            got.add(mutable.asLong());
                        } else {
                            Handles.CONSUME.invokeExact(context, (BlockPos) mutable);
                        }
                    }
                }
            }
        }
        ASKED.add(asked);
        if (got != null) {
            // canPlaceAt reads only its own block, so the blocks placed afterwards are the ones placed along the way
            VERIFY.result(expected.equals(got), "cluster part at " + center + " in chunk " + (chunkX >> 4) + "," + (chunkZ >> 4), expected.size() + " blocks", got.size() + " blocks");
            for (int i = 0; i < got.size(); i++) {
                Handles.CONSUME.invokeExact(context, (BlockPos) mutable.set(got.getLong(i)));
            }
        }
    }

    /** The blocks Zeta's own loop places, in Zeta's order. Nothing is placed, and both tests are free of side effects. */
    private static LongArrayList zetaLoop(Object shape, Object context, int chunkX, int chunkZ, int minY, int maxY) throws Throwable {
        LongArrayList out = new LongArrayList();
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(0, 0, 0);
        for (int x = 0; x < 16; x++) {
            for (int y = minY; y < maxY; y++) {
                for (int z = 0; z < 16; z++) {
                    mutable.set(chunkX + x, y, chunkZ + z);
                    if ((boolean) Handles.CAN_PLACE.invokeExact(context, (BlockPos) mutable) && (boolean) Handles.IS_INSIDE.invokeExact(shape, (BlockPos) mutable)) {
                        out.add(mutable.asLong());
                    }
                }
            }
        }
        return out;
    }
}
