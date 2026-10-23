package dev.denismasterherobrine.generatoraccelerator2.beardifier;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.annotation.Nullable;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import org.slf4j.Logger;

/**
 * The terrain adaptation YUNG's API and Integrated API add around their structure pieces, as data the GPU's beardifier computes
 * with vanilla's pieces ({@code gpu.codegen.ClEmitter}, {@code enh_factor}).
 *
 * <p>Both mods keep their own piece and junction lists on the beardifier and add their density at the end of
 * {@code Beardifier.compute} ({@link BeardExtras}). A piece contributes {@code factor(dx, dy, dz, yRel) * 0.8}, a junction
 * {@code factor(dx, dy, dz, dy) * 0.4}, where {@code dx, dy, dz} are the block's distances to the piece's box (0 inside) and
 * {@code factor} reads the adaptation's own kernel, a {@code size^3} table of floats, at the distances plus the kernel radius
 * ({@code size / 2}), then scales it by the block's height above the piece's base as vanilla's beard does. YUNG's API tests the
 * piece's box moved by the adaptation's bottom offset and grown by its padding, oriented by the piece's rotation, and lets the
 * top and bottom of a piece carve, bury or do nothing ({@code -1, 1, 0} on the absolute term). Integrated API keeps the box,
 * may invert the piece (its base at the top) and lets an adaptation beard (positive term) or carve (negative term) or both.
 * YUNG's API also marks every block with a nonzero term of a piece whose adaptation names an aquifer override, and at the end of
 * the fill turns water and lava at marked blocks into the override's block (its default block, or a named state). The mask
 * keeps one override, the last one set, so a chunk is given to the GPU only when every override its pieces name is the same.
 *
 * <p>{@link #density} is the Java form of the mods' {@code computeDensity}, transcribed from their bytecode: the reference the
 * GPU test compares with, and what the test hook ({@link #testChunks}) adds on the CPU. In the game the CPU runs the mods' own
 * code ({@link BeardExtras}); only the GPU uses this data, read off the mods' objects by reflection ({@link #of}).
 */
public final class EnhancedBeard {
    private EnhancedBeard() {}

    private static final Logger LOGGER = Ga2Log.get("Beardifier");

    public enum Kind { YUNG, INTEGRATED }

    public static final int OVERRIDE_NONE = 0;
    public static final int OVERRIDE_SOLIDIFY = 1;
    public static final int OVERRIDE_REPLACE = 2;

    /**
     * An adaptation: its kernel and how a block's distances turn into density. {@code topMod}/{@code bottomMod} are YUNG's
     * actions, {@code carves}/{@code beards}/{@code inverted} Integrated API's. The override is YUNG's aquifer override.
     */
    public record Adaptation(Kind kind, int size, float[] kernel, int topMod, int bottomMod, boolean carves, boolean beards, boolean inverted,
                             int overrideKind, @Nullable BlockState overrideState) {
        public int radius() {
            return size / 2;
        }

        /** The same override (kind and state) as {@code o}. */
        boolean sameOverride(Adaptation o) {
            return overrideKind == o.overrideKind && overrideState == o.overrideState;
        }
    }

    /** A piece's box as the mod tests it, with its adaptation (null: the mod's NONE, which adds nothing). */
    public record Piece(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, @Nullable Adaptation adaptation) {}

    /** A junction: its source position (YUNG's with the bottom offset added to the ground level) and adaptation (never null). */
    public record Junction(int x, int groundY, int z, Adaptation adaptation) {}

    /** One chunk's enhanced pieces and junctions, in the mod's list order, and the one aquifer override its pieces may name. */
    public record Chunk(Kind kind, List<Piece> pieces, List<Junction> junctions, @Nullable Adaptation override) {
        public boolean isEmpty() {
            return pieces.isEmpty() && junctions.isEmpty();
        }
    }

    /** Tests: enhanced pieces for every chunk without the mods. The CPU's piece index then adds {@link #density} as the mods would. */
    public static volatile Function<ChunkPos, Chunk> testChunks;

    // ---- the mods' formulas

    /** {@code Mth.invSqrt}. */
    static double invSqrt(double n) {
        double d0 = 0.5 * n;
        long i = Double.doubleToRawLongBits(n);
        i = 6910469410427058090L - (i >> 1);
        n = Double.longBitsToDouble(i);
        return n * (1.5 - d0 * n * n);
    }

    /** {@code EnhancedTerrainAdaptation.computeDensityFactor} of either mod. */
    public static double factor(Adaptation a, int dx, int dy, int dz, int yRel) {
        int r = a.radius();
        int i = dx + r;
        int j = dy + r;
        int k = dz + r;
        int size = a.size;
        if (!(i >= 0 && i < size && j >= 0 && j < size && k >= 0 && k < size)) {
            return 0.0;
        }
        float kv = a.kernel[k * size * size + i * size + j];
        double d0 = (double) yRel + 0.5;
        double d1 = (double) dx * (double) dx + d0 * d0 + (double) dz * (double) dz; // Mth.lengthSquared
        if (a.kind == Kind.YUNG) {
            double d2 = Math.abs(d0 * invSqrt(d1 / 2.0) / 2.0);
            int mod = d0 > 0.0 ? a.topMod : a.bottomMod;
            return d2 * (double) kv * (double) mod;
        }
        double d2 = -d0 * invSqrt(d1 / 2.0) / 2.0;
        if (d2 > 0.0 && !a.beards) {
            return 0.0;
        }
        if (d2 < 0.0 && !a.carves) {
            return 0.0;
        }
        return d2 * (double) kv;
    }

    /**
     * The density the mod adds at the block ({@code EnhancedBeardifierHelper.computeDensity} without the sum it is added to).
     * {@code marked[0]} is set when a YUNG piece with an override has a nonzero term here (the block is in the override mask).
     */
    public static double density(Chunk c, int x, int y, int z, @Nullable boolean[] marked) {
        double sum = 0.0;
        for (Piece p : c.pieces) {
            Adaptation a = p.adaptation;
            if (a == null) {
                continue;
            }
            int i1 = Math.max(0, Math.max(p.minX - x, x - p.maxX));
            int j1 = Math.max(0, Math.max(p.minY - y, y - p.maxY));
            int k1 = Math.max(0, Math.max(p.minZ - z, z - p.maxZ));
            int yRel = a.inverted ? p.maxY - y : y - p.minY;
            double v = factor(a, i1, j1, k1, yRel) * 0.8;
            sum += v;
            if (marked != null && v != 0.0 && a.overrideKind != OVERRIDE_NONE) {
                marked[0] = true;
            }
        }
        for (Junction j : c.junctions) {
            Adaptation a = j.adaptation;
            int dx = x - j.x;
            int dy = a.inverted ? j.groundY - y : y - j.groundY;
            int dz = z - j.z;
            double v = factor(a, dx, dy, dz, dy) * 0.4;
            sum += v;
            if (marked != null && v != 0.0 && a.overrideKind != OVERRIDE_NONE) {
                marked[0] = true;
            }
        }
        return sum;
    }

    // ---- the GPU's form

    /** Ints per adaptation in the GPU's table: size, kernel offset (ints into the same table), top and bottom modifiers, carves, beards, inverted, kind, override kind, override id. */
    public static final int TABLE_STRIDE = 10;
    /** Ints per enhanced entry, laid out like a vanilla piece: box or source, adaptation index, flags ({@link #FLAG_JUNCTION}, {@link #FLAG_INVERTED}). */
    public static final int ENTRY_STRIDE = 8;
    public static final int FLAG_JUNCTION = 1;
    public static final int FLAG_INVERTED = 2;

    /** The adaptations of a batch, numbered as they are met, with their kernels after the table. */
    public static final class Table {
        private final Map<Adaptation, Integer> index = new IdentityHashMap<>();
        private final List<Adaptation> list = new ArrayList<>();
        private int floats;

        public int indexOf(Adaptation a) {
            Integer i = index.get(a);
            if (i == null) {
                i = list.size();
                index.put(a, i);
                list.add(a);
                floats += a.kernel.length;
            }
            return i;
        }

        public int ints() {
            return list.size() * TABLE_STRIDE + floats;
        }

        /**
         * The table: the entries, then every kernel's floats as raw int bits. {@code overrideId} gives the GPU's block id of an
         * adaptation's override (by its state, or the default block for solidify), or -1 when the program has none.
         */
        public int[] pack(Function<Adaptation, Integer> overrideId) {
            int[] out = new int[ints()];
            int off = list.size() * TABLE_STRIDE;
            for (int i = 0; i < list.size(); i++) {
                Adaptation a = list.get(i);
                int b = i * TABLE_STRIDE;
                out[b] = a.size;
                out[b + 1] = off;
                out[b + 2] = a.topMod;
                out[b + 3] = a.bottomMod;
                out[b + 4] = a.carves ? 1 : 0;
                out[b + 5] = a.beards ? 1 : 0;
                out[b + 6] = a.inverted ? 1 : 0;
                out[b + 7] = a.kind == Kind.YUNG ? 0 : 1;
                out[b + 8] = a.overrideKind;
                out[b + 9] = a.overrideKind == OVERRIDE_NONE ? -1 : overrideId.apply(a);
                for (float f : a.kernel) {
                    out[off++] = Float.floatToRawIntBits(f);
                }
            }
            return out;
        }
    }

    /** The chunk's entries ({@link #ENTRY_STRIDE} ints each) over {@code table}, pieces then junctions, in the mod's order. */
    public static int[] entries(Chunk c, Table table) {
        int[] out = new int[(c.pieces.size() + c.junctions.size()) * ENTRY_STRIDE];
        int o = 0;
        for (Piece p : c.pieces) {
            out[o] = p.minX;
            out[o + 1] = p.minY;
            out[o + 2] = p.minZ;
            out[o + 3] = p.maxX;
            out[o + 4] = p.maxY;
            out[o + 5] = p.maxZ;
            out[o + 6] = p.adaptation == null ? -1 : table.indexOf(p.adaptation);
            out[o + 7] = p.adaptation != null && p.adaptation.inverted ? FLAG_INVERTED : 0;
            o += ENTRY_STRIDE;
        }
        for (Junction j : c.junctions) {
            out[o] = j.x;
            out[o + 1] = j.groundY;
            out[o + 2] = j.z;
            out[o + 6] = table.indexOf(j.adaptation);
            out[o + 7] = FLAG_JUNCTION | (j.adaptation.inverted ? FLAG_INVERTED : 0);
            o += ENTRY_STRIDE;
        }
        return out;
    }

    // ---- reading the mods' objects

    private static final String YUNG_PKG = "com.yungnickyoung.minecraft.yungsapi.world.structure.terrainadaptation.";
    private static final String INT_PKG = "com.craisinlord.integrated_api.world.terrainadaptation.";

    /** The reflective handles of one mod's classes, resolved once. */
    private static final class Access {
        final Kind kind;
        final MethodHandle box, adaptation, rotation, junction, junctionAdaptation;
        final MethodHandle kernelSize, kernel, bottomOffset, padding, padX, padTop, padBottom, padZ, topAction, bottomAction, modifier, override;
        final MethodHandle carves, beards, direction, inverted;
        final Object none;
        final Class<?> overrideNone, overrideSolidify, overrideReplace;
        final MethodHandle overrideState;
        /** Adaptations read so far, by identity: the same object every chunk. */
        final Map<Object, Adaptation> adaptations = new java.util.concurrent.ConcurrentHashMap<>();

        Access(Kind kind) throws ReflectiveOperationException {
            this.kind = kind;
            ClassLoader cl = EnhancedBeard.class.getClassLoader();
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            String pkg = kind == Kind.YUNG ? YUNG_PKG : INT_PKG;
            Class<?> rigid = Class.forName(pkg + "beardifier.EnhancedBeardifierRigid", false, cl);
            Class<?> junctionClass = Class.forName(pkg + "beardifier.EnhancedJigsawJunction", false, cl);
            Class<?> adapt = Class.forName(kind == Kind.YUNG ? pkg + "adaptations.EnhancedTerrainAdaptation" : pkg + "EnhancedTerrainAdaptation", false, cl);
            box = lookup.unreflect(rigid.getMethod("pieceBoundingBox")).asType(MethodType.methodType(BoundingBox.class, Object.class));
            adaptation = lookup.unreflect(rigid.getMethod("pieceTerrainAdaptation")).asType(MethodType.methodType(Object.class, Object.class));
            rotation = kind == Kind.YUNG ? lookup.unreflect(rigid.getMethod("rotation")).asType(MethodType.methodType(Rotation.class, Object.class)) : null;
            junction = lookup.unreflect(junctionClass.getMethod("jigsawJunction")).asType(MethodType.methodType(JigsawJunction.class, Object.class));
            junctionAdaptation = lookup.unreflect(junctionClass.getMethod("pieceTerrainAdaptation")).asType(MethodType.methodType(Object.class, Object.class));
            kernelSize = lookup.unreflect(adapt.getMethod("getKernelSize")).asType(MethodType.methodType(int.class, Object.class));
            kernel = lookup.unreflect(adapt.getMethod("getKernel")).asType(MethodType.methodType(float[].class, Object.class));
            none = adapt.getField("NONE").get(null);
            if (kind == Kind.YUNG) {
                bottomOffset = lookup.unreflect(adapt.getMethod("getBottomOffset")).asType(MethodType.methodType(double.class, Object.class));
                Class<?> paddingClass = Class.forName(pkg + "adaptations.EnhancedTerrainAdaptation$Padding", false, cl);
                padding = lookup.unreflect(adapt.getMethod("getPadding")).asType(MethodType.methodType(Object.class, Object.class));
                padX = lookup.unreflect(paddingClass.getMethod("x")).asType(MethodType.methodType(int.class, Object.class));
                padTop = lookup.unreflect(paddingClass.getMethod("top")).asType(MethodType.methodType(int.class, Object.class));
                padBottom = lookup.unreflect(paddingClass.getMethod("bottom")).asType(MethodType.methodType(int.class, Object.class));
                padZ = lookup.unreflect(paddingClass.getMethod("z")).asType(MethodType.methodType(int.class, Object.class));
                Class<?> action = Class.forName(pkg + "adaptations.EnhancedTerrainAdaptation$TerrainAction", false, cl);
                topAction = lookup.unreflect(adapt.getMethod("topAction")).asType(MethodType.methodType(Object.class, Object.class));
                bottomAction = lookup.unreflect(adapt.getMethod("bottomAction")).asType(MethodType.methodType(Object.class, Object.class));
                modifier = lookup.unreflect(action.getMethod("getDensityModifier")).asType(MethodType.methodType(int.class, Object.class));
                override = lookup.unreflect(adapt.getMethod("getAquiferOverride")).asType(MethodType.methodType(Object.class, Object.class));
                overrideNone = Class.forName(pkg + "aquiferoverride.NoneAquiferOverride", false, cl);
                overrideSolidify = Class.forName(pkg + "aquiferoverride.SolidifyAquiferOverride", false, cl);
                overrideReplace = Class.forName(pkg + "aquiferoverride.ReplaceAquiferOverride", false, cl);
                Class<?> overrideClass = Class.forName(pkg + "aquiferoverride.AquiferOverride", false, cl);
                overrideState = lookup.unreflect(overrideClass.getMethod("getBlockState", BlockState.class)).asType(MethodType.methodType(BlockState.class, Object.class, BlockState.class));
                carves = null;
                beards = null;
                direction = null;
                inverted = null;
            } else {
                bottomOffset = null;
                padding = null;
                padX = null;
                padTop = null;
                padBottom = null;
                padZ = null;
                topAction = null;
                bottomAction = null;
                modifier = null;
                override = null;
                overrideNone = null;
                overrideSolidify = null;
                overrideReplace = null;
                overrideState = null;
                carves = lookup.unreflect(adapt.getMethod("carves")).asType(MethodType.methodType(boolean.class, Object.class));
                beards = lookup.unreflect(adapt.getMethod("beards")).asType(MethodType.methodType(boolean.class, Object.class));
                Class<?> dir = Class.forName(pkg + "TerrainAdaptationDirection", false, cl);
                direction = lookup.unreflect(adapt.getMethod("direction")).asType(MethodType.methodType(Object.class, Object.class));
                inverted = lookup.unreflect(dir.getMethod("isInverted")).asType(MethodType.methodType(boolean.class, Object.class));
            }
        }

        /** The adaptation record of the mod's object, or null for the mod's NONE. */
        @Nullable
        Adaptation adaptation(Object o) throws Throwable {
            if (o == none) {
                return null;
            }
            Adaptation a = adaptations.get(o);
            if (a != null) {
                return a;
            }
            int size = (int) kernelSize.invokeExact(o);
            float[] k = ((float[]) kernel.invokeExact(o)).clone();
            if (k.length != size * size * size) {
                throw new IllegalStateException("kernel of " + k.length + " floats for size " + size);
            }
            if (kind == Kind.YUNG) {
                int top = (int) modifier.invokeExact((Object) topAction.invokeExact(o));
                int bottom = (int) modifier.invokeExact((Object) bottomAction.invokeExact(o));
                Object ov = (Object) override.invokeExact(o);
                int overrideKind;
                BlockState state = null;
                if (ov == null || overrideNone.isInstance(ov)) {
                    overrideKind = OVERRIDE_NONE;
                } else if (overrideSolidify.isInstance(ov)) {
                    overrideKind = OVERRIDE_SOLIDIFY;
                } else if (overrideReplace.isInstance(ov)) {
                    overrideKind = OVERRIDE_REPLACE;
                    state = (BlockState) overrideState.invokeExact(ov, (BlockState) null); // returns its own state whatever it is given
                } else {
                    throw new IllegalStateException("unknown aquifer override " + ov.getClass().getName());
                }
                a = new Adaptation(kind, size, k, top, bottom, false, false, false, overrideKind, state);
            } else {
                boolean inv = (boolean) inverted.invokeExact((Object) direction.invokeExact(o));
                a = new Adaptation(kind, size, k, 0, 0, (boolean) carves.invokeExact(o), (boolean) beards.invokeExact(o), inv, OVERRIDE_NONE, null);
            }
            adaptations.put(o, a);
            return a;
        }

        /** The piece as the mod tests it. */
        Piece piece(Object rigid) throws Throwable {
            BoundingBox b = (BoundingBox) box.invokeExact(rigid);
            Adaptation a = adaptation((Object) adaptation.invokeExact(rigid));
            if (kind == Kind.YUNG && a != null) {
                Object adaptObj = (Object) adaptation.invokeExact(rigid);
                Rotation rot = (Rotation) rotation.invokeExact(rigid);
                b = b.moved(0, (int) (double) bottomOffset.invokeExact(adaptObj), 0);
                Object pad = (Object) padding.invokeExact(adaptObj);
                boolean alongX = rot.rotate(Direction.EAST).getAxis() == Direction.Axis.X;
                int px = alongX ? (int) padX.invokeExact(pad) : (int) padZ.invokeExact(pad);
                int pz = alongX ? (int) padZ.invokeExact(pad) : (int) padX.invokeExact(pad);
                b = b.inflatedBy(px, 0, pz);
                int top = (int) padTop.invokeExact(pad);
                if (top != 0) {
                    b = new BoundingBox(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY() + top, b.maxZ());
                }
                int bottom = (int) padBottom.invokeExact(pad);
                if (bottom != 0) {
                    b = new BoundingBox(b.minX(), b.minY() - bottom, b.minZ(), b.maxX(), b.maxY(), b.maxZ());
                }
            } else if (kind == Kind.YUNG) {
                // the mod moves and pads the box before it tests the adaptation, but with NONE nothing is computed from it
                return new Piece(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ(), null);
            }
            return new Piece(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ(), a);
        }

        Junction junction(Object j) throws Throwable {
            JigsawJunction jj = (JigsawJunction) junction.invokeExact(j);
            Object adaptObj = (Object) junctionAdaptation.invokeExact(j);
            Adaptation a = adaptation(adaptObj);
            int groundY = jj.getSourceGroundY();
            if (kind == Kind.YUNG) {
                // the mod reads the offset of the NONE adaptation too (0.0 for NONE)
                groundY += (int) (double) bottomOffset.invokeExact(adaptObj);
            }
            if (a == null) {
                a = noneAdaptation();
            }
            return new Junction(jj.getSourceX(), groundY, jj.getSourceZ(), a);
        }

        private volatile Adaptation noneJunction;

        /** A junction's NONE adaptation computes its factor like any other (a kernel of size 0 gives 0). */
        Adaptation noneAdaptation() throws Throwable {
            Adaptation a = noneJunction;
            if (a == null) {
                int size = (int) kernelSize.invokeExact(none);
                float[] k = ((float[]) kernel.invokeExact(none)).clone();
                a = kind == Kind.YUNG
                    ? new Adaptation(kind, size, k, (int) modifier.invokeExact((Object) topAction.invokeExact(none)),
                        (int) modifier.invokeExact((Object) bottomAction.invokeExact(none)), false, false, false, OVERRIDE_NONE, null)
                    : new Adaptation(kind, size, k, 0, 0, (boolean) carves.invokeExact(none), (boolean) beards.invokeExact(none),
                        (boolean) inverted.invokeExact((Object) direction.invokeExact(none)), OVERRIDE_NONE, null);
                noneJunction = a;
            }
            return a;
        }
    }

    private static final Map<Kind, Access> ACCESS = new java.util.EnumMap<>(Kind.class);
    private static final Map<Kind, Boolean> FAILED = new java.util.EnumMap<>(Kind.class);

    @Nullable
    private static synchronized Access access(Kind kind) {
        Access a = ACCESS.get(kind);
        if (a != null || FAILED.containsKey(kind)) {
            return a;
        }
        try {
            a = new Access(kind);
            ACCESS.put(kind, a);
            return a;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warn("{} terrain adaptation not readable ({}) -> chunks with its pieces stay on the CPU", kind, e.toString());
            FAILED.put(kind, Boolean.TRUE);
            return null;
        }
    }

    /**
     * The chunk's enhanced pieces and junctions from the mod's two list iterators (left where they were), or null when they cannot
     * be read, or when the pieces name different aquifer overrides (the mask keeps one, the last set, which the GPU cannot order).
     */
    @Nullable
    public static Chunk of(Kind kind, @Nullable Object pieces, @Nullable Object junctions) {
        Access acc = access(kind);
        if (acc == null) {
            return null;
        }
        try {
            List<Piece> ps = new ArrayList<>();
            List<Junction> js = new ArrayList<>();
            Adaptation override = null;
            if (pieces instanceof Iterator<?> it) {
                while (it.hasNext()) {
                    Piece p = acc.piece(it.next());
                    ps.add(p);
                    if (p.adaptation != null && p.adaptation.overrideKind != OVERRIDE_NONE) {
                        if (override != null && !override.sameOverride(p.adaptation)) {
                            return null;
                        }
                        override = p.adaptation;
                    }
                }
                rewind(it);
            }
            if (junctions instanceof Iterator<?> it) {
                while (it.hasNext()) {
                    Junction j = acc.junction(it.next());
                    js.add(j);
                    if (j.adaptation.overrideKind != OVERRIDE_NONE) {
                        if (override != null && !override.sameOverride(j.adaptation)) {
                            return null;
                        }
                        override = j.adaptation;
                    }
                }
                rewind(it);
            }
            return new Chunk(kind, ps, js, override);
        } catch (Throwable t) {
            LOGGER.warn("{} terrain adaptation not readable ({}) -> chunk on the CPU", kind, t.toString());
            return null;
        }
    }

    private static void rewind(Iterator<?> it) {
        if (it instanceof it.unimi.dsi.fastutil.objects.ObjectListIterator<?> li) {
            li.back(Integer.MAX_VALUE);
        }
    }
}
