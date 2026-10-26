package dev.denismasterherobrine.generatoraccelerator2.gpu.light;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * What the light propagation reads of a block, as types for the GPU: the opacity ({@code getLightBlock}) and the six faces
 * {@code LightEngine.getOcclusionShape} gives (an empty shape for a block without one). Faces are numbered by shape object; the
 * GPU decides occlusion from whether a face covers the whole face of the block on its own, and for two faces that do not, from
 * {@code Shapes.faceShapeOccludes} of the pair.
 *
 * <p>A block state with a cached shape answers the same anywhere, so it is one type. A state without (a dynamic shape: shulker
 * boxes, moving pistons) may answer by position. Each such block gets a type of its own, asked at its position.
 *
 * <p>Face numbers, and the answers for face pairs, are kept across batches: the faces of cached states are the cache's own
 * objects. Types are numbered per batch. Type 0 is a block without occlusion shape (the source of an entry from an empty shape).
 */
final class LightTypes {
    /** Face numbers of shape objects, across batches. 0 is the empty shape. */
    private static final Map<VoxelShape, Integer> FACES = new IdentityHashMap<>();
    private static final List<VoxelShape> FACE_LIST = new ArrayList<>();
    private static final List<Boolean> FACE_COVERS = new ArrayList<>();
    /** {@code faceShapeOccludes} of two faces that do not cover on their own, by their numbers. */
    private static final Long2ByteOpenHashMap PAIRS = new Long2ByteOpenHashMap();
    /** Per cached state: its opacity and six face numbers. */
    private static final Map<BlockState, int[]> STATES = new IdentityHashMap<>();

    static {
        face(Shapes.empty());
    }

    /** The batch's types: per type its opacity and its six face numbers. */
    private final Map<BlockState, Integer> index = new IdentityHashMap<>();
    int count;
    byte[] opacity = new byte[256];
    short[] faces = new short[256 * 6];
    /** The faces the batch uses, renumbered from 0 (the empty shape). */
    private final Int2IntOpenHashMap localFace = new Int2IntOpenHashMap();
    private final IntArrayList faceGlobal = new IntArrayList();

    void reset() {
        index.clear();
        count = 0;
        localFace.clear();
        faceGlobal.clear();
        add(0, new int[] { 0, 0, 0, 0, 0, 0, 0 });
    }

    /** The type of {@code state}, a cached-shape state. */
    int type(BlockState state) {
        Integer t = index.get(state);
        if (t != null) {
            return t;
        }
        int[] props;
        synchronized (LightTypes.class) {
            props = STATES.get(state);
            if (props == null) {
                props = props(state, EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                STATES.put(state, props);
            }
        }
        int id = add(-1, props);
        index.put(state, id);
        return id;
    }

    /** A type of its own for {@code state} at {@code pos} (a dynamic shape), asked of {@code level} there. */
    int typeAt(BlockState state, BlockGetter level, BlockPos pos) {
        int[] props;
        synchronized (LightTypes.class) {
            props = props(state, level, pos);
        }
        return add(-1, props);
    }

    /** Whether {@code state}'s light properties may depend on the position (no shape cache). */
    static boolean dynamic(BlockState state) {
        return state.getBlock().hasDynamicShape();
    }

    private int add(int fixed, int[] props) {
        int id = fixed >= 0 ? fixed : count;
        if (id >= 65536) {
            throw new IllegalStateException("more than 65536 light types in a batch");
        }
        if (id >= count) {
            count = id + 1;
        }
        if (opacity.length < count) {
            opacity = Arrays.copyOf(opacity, Math.max(count, opacity.length * 2));
            faces = Arrays.copyOf(faces, opacity.length * 6);
        }
        opacity[id] = (byte) props[0];
        for (int d = 0; d < 6; d++) {
            faces[id * 6 + d] = (short) local(props[1 + d]);
        }
        return id;
    }

    private int local(int global) {
        int l = localFace.getOrDefault(global, -1);
        if (l < 0) {
            l = faceGlobal.size();
            faceGlobal.add(global);
            localFace.put(global, l);
        }
        return l;
    }

    /** Must hold the class lock. The light properties of {@code state} at {@code pos} as the light engine asks them. */
    private static int[] props(BlockState state, BlockGetter level, BlockPos pos) {
        int[] p = new int[7];
        p[0] = state.getLightBlock(level, pos);
        boolean empty = !state.canOcclude() || !state.useShapeForLightOcclusion();
        for (Direction d : Direction.values()) {
            p[1 + d.ordinal()] = empty ? 0 : face(state.getFaceOcclusionShape(level, pos, d));
        }
        return p;
    }

    /** Must hold the class lock. The number of a face shape. */
    private static int face(VoxelShape shape) {
        Integer f = FACES.get(shape);
        if (f != null) {
            return f;
        }
        int id = FACE_LIST.size();
        FACES.put(shape, id);
        FACE_LIST.add(shape);
        FACE_COVERS.add(Shapes.faceShapeOccludes(shape, Shapes.empty()));
        return id;
    }

    /** The batch's face tables: per local face whether it covers, its partial index (-1 if it covers or is empty), the pair bits. */
    record Faces(byte[] cover, short[] part, int[] pairs, int partials) {}

    Faces faceTables() {
        int n = faceGlobal.size();
        byte[] cover = new byte[Math.max(1, n)];
        short[] part = new short[Math.max(1, n)];
        int[] partials = new int[n];
        int np = 0;
        synchronized (LightTypes.class) {
            for (int l = 0; l < n; l++) {
                int g = faceGlobal.getInt(l);
                boolean covers = FACE_COVERS.get(g);
                cover[l] = (byte) (covers ? 1 : 0);
                if (!covers && g != 0) {
                    part[l] = (short) np;
                    partials[np++] = g;
                } else {
                    part[l] = -1;
                }
            }
            int[] pairs = new int[Math.max(1, (np * np + 31) >> 5)];
            for (int a = 0; a < np; a++) {
                for (int b = 0; b < np; b++) {
                    long key = (long) partials[a] << 32 | partials[b];
                    byte v = PAIRS.getOrDefault(key, (byte) -1);
                    if (v < 0) {
                        v = (byte) (Shapes.faceShapeOccludes(FACE_LIST.get(partials[a]), FACE_LIST.get(partials[b])) ? 1 : 0);
                        PAIRS.put(key, v);
                    }
                    if (v != 0) {
                        int bit = a * np + b;
                        pairs[bit >> 5] |= 1 << (bit & 31);
                    }
                }
            }
            return new Faces(cover, part, pairs, np);
        }
    }
}
